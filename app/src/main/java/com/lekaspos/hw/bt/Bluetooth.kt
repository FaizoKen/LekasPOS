package com.lekaspos.hw.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
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
 * scanners in SPP mode, and last RFCOMM channel 1 directly, for devices whose service lookup
 * fails ("read failed, socket might closed"). One-shot: once [close]d (from any thread, also
 * while [open] is still connecting) it stays closed.
 */
class SppLink(private val adapter: BluetoothAdapter, val address: String) : Closeable {

    // Set before connect(), so that close() from another thread can abort a connect in progress.
    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var connected = false

    @Volatile
    private var closed = false

    val isOpen: Boolean get() = connected && !closed

    @SuppressLint("MissingPermission") // callers check Bluetooth.hasPermission()
    @Throws(IOException::class)
    fun open() {
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw IOException("invalid Bluetooth address", e)
        }
        var last: IOException? = null
        for (attempt in 0 until 3) {
            if (closed) throw IOException("closed")
            val s = try {
                when (attempt) {
                    0 -> device.createRfcommSocketToServiceRecord(Bluetooth.SPP)
                    1 -> device.createInsecureRfcommSocketToServiceRecord(Bluetooth.SPP)
                    else -> channelOne(device) ?: break
                }
            } catch (e: IOException) {
                last = e
                continue
            }
            socket = s
            try {
                if (closed) throw IOException("closed") // close() may have run before it could see s
                s.connect()
                if (closed) throw IOException("closed")
                connected = true
                return
            } catch (e: IOException) {
                last = e
                socket = null
                closeQuietly(s)
            } catch (e: SecurityException) {
                socket = null
                closeQuietly(s)
                throw e
            }
        }
        if (closed) throw IOException("closed")
        throw last ?: IOException("Bluetooth connection failed")
    }

    /**
     * RFCOMM channel 1 without the service lookup: the usual last resort for cheap printers. The
     * method is hidden, so reflection; null where the platform does not offer it.
     */
    @SuppressLint("DiscouragedPrivateApi")
    private fun channelOne(device: BluetoothDevice): BluetoothSocket? = try {
        val create = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        create.invoke(device, 1) as? BluetoothSocket
    } catch (e: Exception) {
        null
    }

    @Throws(IOException::class)
    fun output(): OutputStream = openSocket().outputStream

    @Throws(IOException::class)
    fun input(): InputStream = openSocket().inputStream

    private fun openSocket(): BluetoothSocket {
        val s = socket
        if (s == null || !connected || closed) throw IOException("not connected")
        return s
    }

    override fun close() {
        closed = true // first: an open() that has just set the socket sees it and gives up
        socket?.let { closeQuietly(it) }
        socket = null
        connected = false
    }

    private fun closeQuietly(s: BluetoothSocket) {
        try {
            s.close()
        } catch (e: IOException) {
            // already closed
        }
    }
}
