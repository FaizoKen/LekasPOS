package com.lekaspos.ui.scan

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Camera
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.lekaspos.R
import com.lekaspos.domain.sell.CartSession
import com.lekaspos.hw.camera.BarcodeDecoder
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.sell.Beeper
import com.lekaspos.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.launch

/**
 * Camera barcode scanning with the platform Camera1 API and ZXing (D-025) — no CameraX.
 * "Sell" mode keeps scanning and adds every item to the open bill; "pick" mode returns the
 * first code (e.g. to fill in a product's barcode).
 */
@Suppress("DEPRECATION") // android.hardware.Camera: the only camera API on every supported level
class CameraScanActivity : ScreenActivity(), SurfaceHolder.Callback, Camera.PreviewCallback {

    private var sellMode = true
    private lateinit var surface: SurfaceView
    private lateinit var status: TextView
    private var camera: Camera? = null
    private var previewW = 0
    private var previewH = 0
    private var rotate = false
    private var surfaceReady = false
    private var torchOn = false
    private val decoder = BarcodeDecoder()
    private var executor: ExecutorService? = null
    private var permissionAsked = false

    @Volatile
    private var decoding = false
    private var lastCode: String? = null
    private var lastAt = 0L
    private var beeper: Beeper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sellMode = intent.getBooleanExtra(EXTRA_SELL, true)
        permissionAsked = savedInstanceState?.getBoolean(STATE_ASKED) == true
        val v = setScreen(getString(R.string.camera_title), R.layout.activity_camera) ?: return
        surface = v.findViewById(R.id.camera_preview)
        status = v.findViewById(R.id.camera_status)
        status.setText(R.string.camera_hint)
        status.setOnClickListener { onStatusClick() }
        surface.holder.addCallback(this)
        addAction(R.drawable.ic_flash, R.string.camera_torch) { toggleTorch() }
        beeper = Beeper.create()
        // After process death Android may restore this screen first: the bill must be loaded.
        if (sellMode) launchUi { graph.cart.load() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ASKED, permissionAsked)
    }

    override fun onResume() {
        super.onResume()
        if (hasPermission()) {
            open()
        } else if (Build.VERSION.SDK_INT >= 23 && !permissionAsked) {
            // Ask once. Asking on every resume looped after "Deny": the answer resumes this screen,
            // which asked again (at once and forever once Android stops showing the question).
            permissionAsked = true
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        } else {
            showDenied()
        }
    }

    override fun onPause() {
        release()
        super.onPause()
    }

    override fun onDestroy() {
        beeper?.release()
        beeper = null // a decode finishing after this must not beep on a released tone generator
        super.onDestroy()
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        if (hasPermission()) open() else showDenied()
    }

    private fun canAskAgain(): Boolean =
        Build.VERSION.SDK_INT >= 23 && shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)

    /** Denied: the status line says so; tapped, it asks again (or opens the app's settings once Android won't). */
    private fun showDenied() {
        status.setText(if (canAskAgain()) R.string.camera_permission_ask else R.string.camera_permission_settings)
    }

    private fun onStatusClick() {
        if (Build.VERSION.SDK_INT < 23) return
        if (hasPermission() || !permissionAsked) return
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        try {
            val app = Uri.fromParts("package", packageName, null)
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app))
        } catch (e: ActivityNotFoundException) {
            status.setText(R.string.camera_permission)
        }
    }

    // ------------------------------------------------------------------ camera

    private fun open() {
        if (camera != null) return
        var cam: Camera? = null
        try {
            val id = (0 until Camera.getNumberOfCameras()).firstOrNull { i ->
                Camera.CameraInfo().also { Camera.getCameraInfo(i, it) }.facing == Camera.CameraInfo.CAMERA_FACING_BACK
            } ?: 0
            val c = Camera.open(id)
            cam = c
            configure(c, id)
        } catch (e: RuntimeException) {
            Log.e("Camera open failed", e)
            cam?.release() // opened but not configurable: unreleased, it stays locked for every app
            status.setText(R.string.camera_unavailable)
            return
        }
        camera = cam
        executor = Executors.newSingleThreadExecutor { r -> Thread(r, "barcode-decode") }
        if (surfaceReady) startPreview()
    }

    private fun configure(cam: Camera, id: Int) {
        val p = cam.parameters
        val size = p.supportedPreviewSizes
            .filter { it.width <= 1280 && it.height <= 960 }
            .maxByOrNull { it.width * it.height } ?: p.previewSize
        p.setPreviewSize(size.width, size.height)
        previewW = size.width
        previewH = size.height
        val modes = p.supportedFocusModes ?: emptyList()
        when {
            Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE in modes -> p.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            Camera.Parameters.FOCUS_MODE_AUTO in modes -> p.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
        }
        cam.parameters = p
        val info = Camera.CameraInfo()
        Camera.getCameraInfo(id, info)
        val degrees = when (displayRotation()) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val orientation = (info.orientation - degrees + 360) % 360
        cam.setDisplayOrientation(orientation)
        rotate = orientation == 90 || orientation == 270
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = windowManager.defaultDisplay.rotation

    private fun startPreview() {
        val c = camera ?: return
        try {
            c.setPreviewDisplay(surface.holder)
            val buffer = ByteArray(previewW * previewH * 3 / 2)
            c.addCallbackBuffer(buffer)
            c.setPreviewCallbackWithBuffer(this)
            c.startPreview()
            if (c.parameters.focusMode == Camera.Parameters.FOCUS_MODE_AUTO) c.autoFocus { _, _ -> }
        } catch (e: Exception) {
            Log.e("Camera preview failed", e)
            status.setText(R.string.camera_unavailable)
        }
    }

    private fun release() {
        camera?.let {
            it.setPreviewCallbackWithBuffer(null)
            it.stopPreview()
            it.release()
        }
        camera = null
        torchOn = false
        executor?.shutdownNow()
        executor = null
        decoding = false // a decode still running reports to the old executor and is ignored
    }

    private fun toggleTorch() {
        val c = camera ?: return
        try {
            val p = c.parameters
            val modes = p.supportedFlashModes ?: return
            if (Camera.Parameters.FLASH_MODE_TORCH !in modes) return
            torchOn = !torchOn
            p.flashMode = if (torchOn) Camera.Parameters.FLASH_MODE_TORCH else Camera.Parameters.FLASH_MODE_OFF
            c.parameters = p
        } catch (e: RuntimeException) {
            Log.w("Torch failed", e)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        startPreview()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    // ------------------------------------------------------------------ decoding

    override fun onPreviewFrame(data: ByteArray, cam: Camera) {
        val ex = executor
        if (decoding || ex == null) {
            cam.addCallbackBuffer(data)
            return
        }
        decoding = true
        val w = previewW
        val h = previewH
        val turn = rotate
        try {
            ex.execute {
                val code = try {
                    decoder.decode(data, w, h, turn)
                } catch (e: RuntimeException) {
                    null
                }
                runOnUiThread {
                    // The camera was released meanwhile (paused, rotated, closed): drop the old frame's result.
                    if (executor !== ex) return@runOnUiThread
                    camera?.addCallbackBuffer(data)
                    decoding = false
                    if (code != null) onCode(code)
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            decoding = false
        }
    }

    private fun onCode(code: String) {
        val now = SystemClock.uptimeMillis()
        if (code == lastCode && now - lastAt < REPEAT_MS) {
            lastAt = now // the same item still in view: it counts again only after leaving the frame
            return
        }
        lastCode = code
        lastAt = now
        graph.staff.touch() // scanning is using the till, though the screen is not touched
        if (!sellMode) {
            beeper?.ok()
            setResult(RESULT_OK, Intent().putExtra(EXTRA_CODE, code))
            finish()
            return
        }
        scope.launch {
            val r = graph.cart.scan(code)
            // As on the selling screen, the beep tells the result: "ok" only when the item was added.
            if (r is CartSession.ScanResult.Added) beeper?.ok() else beeper?.error()
            status.text = when (r) {
                is CartSession.ScanResult.Added -> getString(R.string.camera_added, r.name)
                is CartSession.ScanResult.NotFound -> getString(R.string.camera_not_found, code)
                is CartSession.ScanResult.NeedsWeight -> getString(R.string.camera_needs_input, r.product.name)
                is CartSession.ScanResult.NeedsPrice -> getString(R.string.camera_needs_input, r.product.name)
                CartSession.ScanResult.Busy -> getString(R.string.sell_busy)
            }
        }
    }

    companion object {
        const val EXTRA_CODE = "code"
        private const val EXTRA_SELL = "sell"
        private const val REQ_CAMERA = 31
        private const val REPEAT_MS = 1500L
        private const val STATE_ASKED = "camera.permission_asked"

        fun sellIntent(ctx: Context): Intent = Intent(ctx, CameraScanActivity::class.java).putExtra(EXTRA_SELL, true)

        fun pickIntent(ctx: Context): Intent = Intent(ctx, CameraScanActivity::class.java).putExtra(EXTRA_SELL, false)
    }
}
