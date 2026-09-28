package com.lekaspos.hw.camera

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

/**
 * Decodes retail barcodes (EAN/UPC, Code 128/39) and QR codes from camera preview frames with
 * ZXing core 3.3.3 (D-025). Not thread-safe: one instance per decoding thread.
 */
class BarcodeDecoder {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(
                    BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
                    BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.QR_CODE,
                ),
            ),
        )
    }

    private var rotated: ByteArray? = null

    /**
     * [y] = luminance plane of an NV21 frame ([width] × [height]). [rotate] turns it 90° first
     * (portrait screens), because 1-D readers scan rows and the camera sensor is landscape.
     */
    fun decode(y: ByteArray, width: Int, height: Int, rotate: Boolean): String? {
        var data = y
        var w = width
        var h = height
        if (rotate) {
            val out = rotated?.takeIf { it.size >= width * height } ?: ByteArray(width * height).also { rotated = it }
            for (row in 0 until height) {
                val src = row * width
                for (col in 0 until width) out[col * height + (height - 1 - row)] = y[src + col]
            }
            data = out
            w = height
            h = width
        }
        // The middle of the frame, where the on-screen guide is.
        val cropW = w * 9 / 10
        val cropH = h * 6 / 10
        val source = PlanarYUVLuminanceSource(data, w, h, (w - cropW) / 2, (h - cropH) / 2, cropW, cropH, false)
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (e: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }
}
