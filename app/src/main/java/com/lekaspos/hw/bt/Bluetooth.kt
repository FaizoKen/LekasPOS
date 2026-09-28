package com.lekaspos.hw.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** A paired (bonded) Bluetooth device. */
data class Paired(val name: String, val address: String, val printerLike: Boolean)

/**
 * Bluetooth Classic helpers (D-027): devices are chosen from the phone's paired list, which
 * needs BLUETOOTH_CONNECT on Android 12+ and nothing at runtime before that. Pairing is done
 * in Android's Bluetooth settings.
 */
object Bluetooth {
    val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** Runtime permissions to request before using Bluetooth (empty below Android 12). */
    fun runtimePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()

    fun hasPermission(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun isEnabled(context: Context): Boolean = hasPermission(context) && adapter(context)?.isEnabled == true

    /** Paired devices, printer-like ones first. Empty without permission or Bluetooth. */
    @SuppressLint("MissingPermission") // checked by hasPermission()
    fun paired(context: Context): List<Paired> {
        if (!hasPermission(context)) return emptyList()
        val adapter = adapter(context) ?: return emptyList()
        val devices = try {
            adapter.bondedDevices ?: emptySet()
        } catch (e: SecurityException) {
            return emptyList()
        }
        return devices.map { d ->
            val name = d.name ?: d.address
            val imaging = d.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.IMAGING
            Paired(name, d.address, imaging || PRINTER_NAMES.containsMatchIn(name))
        }.sortedWith(compareBy({ !it.printerLike }, { it.name.lowercase() }))
    }

    fun settingsIntent(): Intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)

    private val PRINTER_NAMES = Regex("print|pos|rpp|mtp|pt-|xp-|ts-|goojprt|thermal", RegexOption.IGNORE_CASE)
}

/**
 * One RFCOMM (SPP) connection. Blocking: use it only on a dedicated background thread.
 * Tries the secure and then the insecure socket, which covers common ESC/POS printers and
 * scanners in SPP mode.
 */
class SppLink(private val adapter: BluetoothAdapter, val address: String) : Closeable {

    @Volatile
    private var socket: BluetoothSocket? = null

    val isOpen: Boolean get() = socket != null

    @SuppressLint("MissingPermission") // callers check Bluetooth.hasPermission()
    @Throws(IOException::class)
    fun open() {
        close()
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw IOException("invalid Bluetooth address", e)
        }
        var last: IOException? = null
        for (secure in booleanArrayOf(true, false)) {
            val s = try {
                if (secure) device.createRfcommSocketToServiceRecord(Bluetooth.SPP) else device.createInsecureRfcommSocketToServiceRecord(Bluetooth.SPP)
            } catch (e: IOException) {
                last = e
                continue
            }
            try {
                s.connect()
                socket = s
                return
            } catch (e: IOException) {
                last = e
                closeQuietly(s)
            }
        }
        throw last ?: IOException("Bluetooth connection failed")
    }

    @Throws(IOException::class)
    fun output(): OutputStream = socket?.outputStream ?: throw IOException("not connected")

    @Throws(IOException::class)
    fun input(): InputStream = socket?.inputStream ?: throw IOException("not connected")

    override fun close() {
        socket?.let { closeQuietly(it) }
        socket = null
    }

    private fun closeQuietly(s: BluetoothSocket) {
        try {
            s.close()
        } catch (e: IOException) {
            // already closed
        }
    }
}
