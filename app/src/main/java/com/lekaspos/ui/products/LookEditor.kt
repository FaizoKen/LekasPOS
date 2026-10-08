package com.lekaspos.ui.products

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import com.lekaspos.R
import com.lekaspos.core.model.TileColor
import com.lekaspos.data.product.ProductLook
import com.lekaspos.ui.colorOf
import com.lekaspos.ui.common.ScreenActivity
import com.lekaspos.ui.common.TileColors
import com.lekaspos.ui.common.ColorPicker
import com.lekaspos.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The product form's "On the selling screen" part (D-066): a preview of the tile's top, Take photo /
 * Choose picture / Remove picture, and the tile colours. What was chosen survives Android ending the
 * app while the camera app is open ([save] / [restore]: a picture is ~13 KB of text).
 */
class LookEditor(private val a: ScreenActivity) {

    /** The look as stored when the form opened (the edit's "before"). */
    var shown = ProductLook()

    private var color = TileColor.NONE
    private var newPicture: String? = null
    private var removed = false
    /** A photo or picture is being read: Save waits for it. */
    var busy = false
        private set

    /** Kept from [restore] until [build] (the form loads after Android restored the screen). */
    private var restored: Bundle? = null

    private var preview: ImageView? = null
    private var removeButton: Button? = null
    private var built = false

    /** What the user chose, with [imageId] for a new picture once it is stored. */
    fun edited(imageId: Long?): ProductLook = ProductLook(
        color = color,
        imageId = when {
            newPicture != null -> imageId
            removed -> null
            else -> shown.imageId
        },
    )

    /** A new picture to store (base64 JPEG), if one was taken or chosen. */
    val picture: String? get() = newPicture

    fun save(out: Bundle) {
        if (!built) {
            restored?.let { out.putBundle(STATE, it) } // not built yet: what was restored, again
            return
        }
        out.putBundle(STATE, Bundle().apply {
            putInt("color", color)
            putString("picture", newPicture)
            putBoolean("removed", removed)
        })
    }

    fun restore(saved: Bundle?) {
        restored = saved?.getBundle(STATE)
    }

    private fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()

    /** The views, for the form; [look] as stored ([shown]). */
    fun build(look: ProductLook): View {
        shown = look
        color = TileColor.known(look.color)
        restored?.let {
            val chosen = it.getInt("color", -1) // -1: none chosen before Android ended the app
            if (chosen >= 0) color = TileColor.known(chosen)
            newPicture = it.getString("picture")
            removed = it.getBoolean("removed")
        }
        restored = null
        val column = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

        val top = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
        }
        val frame = FrameLayout(a)
        val image = ImageView(a).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = a.getString(R.string.look_preview)
            clipToOutline = true
        }
        frame.addView(image, FrameLayout.LayoutParams(dp(PREVIEW_DP), dp(PREVIEW_DP)))
        top.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        preview = image

        val buttons = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        fun button(text: Int, onClick: () -> Unit) = Button(a, null, 0, R.style.Widget_Lekas_Button_Secondary).also {
            it.text = a.getString(text)
            it.setOnClickListener { onClick() }
            buttons.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
        }
        if (a.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) button(R.string.look_take_photo) { takePhoto() }
        button(R.string.look_choose_picture) { choosePicture() }
        removeButton = button(R.string.look_remove_picture) {
            newPicture = null
            removed = true
            showPicture()
        }
        top.addView(buttons, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        column.addView(top)

        column.addView(TextView(a, null, 0, R.style.Text_Lekas_Label).apply { text = a.getString(R.string.look_color) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        val picker = ColorPicker(a, color) {
            color = it
            showPicture()
        }
        column.addView(picker.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        built = true
        column.addView(TextView(a, null, 0, R.style.Text_Lekas_Caption).apply { text = a.getString(R.string.look_help) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        showPicture()
        return column
    }

    /** The preview: the picture (new, stored, or none) on the tile's colour. */
    private fun showPicture() {
        val image = preview ?: return
        image.background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(TileColors.argb(this@LookEditor.color) ?: a.colorOf(R.color.chip_bg))
        }
        val fresh = newPicture
        val storedId = if (removed) null else shown.imageId
        removeButton?.visibility = if (fresh != null || storedId != null) View.VISIBLE else View.GONE
        image.setImageDrawable(null)
        when {
            fresh != null -> a.launchUi {
                val bitmap = withContext(Dispatchers.Default) { Pictures.decode(fresh) }
                if (newPicture === fresh) image.setImageBitmap(bitmap)
            }
            storedId != null -> a.launchUi {
                val bitmap = a.graph.pictures.load(storedId)
                if (newPicture == null && !removed && bitmap != null) image.setImageBitmap(bitmap)
            }
        }
    }

    // ------------------------------------------------------------------ camera and gallery

    private fun takePhoto() {
        if (busy) return
        if (Build.VERSION.SDK_INT >= 23 && a.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_PERMISSION)
            return
        }
        val file = Pictures.captureFile(a)
        file.delete()
        val uri = FileProvider.getUriForFile(a, a.packageName + ".files", file)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("", uri) // the grant reaches the camera app on every version
        try {
            @Suppress("DEPRECATION")
            a.startActivityForResult(intent, REQ_PHOTO)
        } catch (e: ActivityNotFoundException) {
            a.toast(R.string.look_no_camera_app)
        } catch (e: SecurityException) { // a camera app that refuses another app's file
            a.toast(R.string.look_no_camera_app)
        }
    }

    private fun choosePicture() {
        if (busy) return
        val intent = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        try {
            @Suppress("DEPRECATION")
            a.startActivityForResult(intent, REQ_PICK)
        } catch (e: ActivityNotFoundException) {
            a.toast(R.string.look_no_gallery)
        }
    }

    /** The camera permission answered: the photo is taken when allowed. Returns true if it was ours. */
    fun onPermission(requestCode: Int, grants: IntArray): Boolean {
        if (requestCode != REQ_PERMISSION) return false
        if (grants.firstOrNull() == PackageManager.PERMISSION_GRANTED) takePhoto() else a.toast(R.string.look_camera_denied)
        return true
    }

    /** A photo taken or a picture chosen. Returns true if the result was ours. */
    fun onResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQ_PHOTO && requestCode != REQ_PICK) return false
        if (resultCode != android.app.Activity.RESULT_OK) return true
        val picked: Uri? = if (requestCode == REQ_PICK) data?.data ?: return true else null
        val app = a.applicationContext
        busy = true
        a.launchUi {
            try {
                val stored = withContext(Dispatchers.IO) {
                    if (picked != null) Pictures.fromUri(app, picked) else Pictures.fromFile(Pictures.captureFile(app))
                }
                newPicture = stored
                removed = false
                // The form is still loading (Android ended the app while the camera was open): [build] takes it.
                if (built) {
                    showPicture()
                } else {
                    restored = (restored ?: Bundle()).apply {
                        putString("picture", stored)
                        putBoolean("removed", false)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("A product picture could not be read", e)
                a.toast(R.string.look_unreadable)
            } finally {
                busy = false
                if (picked == null) withContext(NonCancellable + Dispatchers.IO) { Pictures.captureFile(app).delete() }
            }
        }
        return true
    }

    private companion object {
        const val STATE = "product.look"
        const val PREVIEW_DP = 112
        const val REQ_PHOTO = 21
        const val REQ_PICK = 22
        const val REQ_PERMISSION = 23
    }
}
