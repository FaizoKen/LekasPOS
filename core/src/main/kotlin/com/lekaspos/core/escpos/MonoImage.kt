package com.lekaspos.core.escpos

/**
 * A 1-bit image in ESC/POS raster layout: rows top to bottom, 8 pixels per byte, most
 * significant bit first, 1 = black (printed dot).
 */
class MonoImage(val width: Int, val height: Int, val data: ByteArray) {

    val bytesPerRow: Int get() = (width + 7) ushr 3

    init {
        require(width > 0 && height >= 0) { "bad size ${width}x$height" }
        require(data.size == bytesPerRow * height) { "data size ${data.size} != ${bytesPerRow * height}" }
    }

    fun isBlack(x: Int, y: Int): Boolean =
        (data[y * bytesPerRow + (x ushr 3)].toInt() and (0x80 ushr (x and 7))) != 0

    /** Rows [from] until [from] + [count] as a new image. */
    fun rows(from: Int, count: Int): MonoImage {
        require(from >= 0 && count >= 0 && from + count <= height) { "rows out of range" }
        return MonoImage(width, count, data.copyOfRange(from * bytesPerRow, (from + count) * bytesPerRow))
    }

    /** The image moved right by [dots] white columns (raster alignment commands are unreliable). */
    fun padLeft(dots: Int): MonoImage {
        require(dots >= 0)
        if (dots == 0) return this
        val w = width + dots
        val bpr = (w + 7) ushr 3
        val out = ByteArray(bpr * height)
        for (y in 0 until height) {
            val row = y * bpr
            for (x in 0 until width) {
                if (isBlack(x, y)) {
                    val nx = x + dots
                    val i = row + (nx ushr 3)
                    out[i] = (out[i].toInt() or (0x80 ushr (nx and 7))).toByte()
                }
            }
        }
        return MonoImage(w, height, out)
    }

    /** Centred on a line [lineDots] wide (unchanged if it is already that wide or wider). */
    fun centered(lineDots: Int): MonoImage = if (width >= lineDots) this else padLeft((lineDots - width) / 2)

    /** Number of rows that contain at least one black dot. */
    fun inkRows(): Int {
        var n = 0
        for (y in 0 until height) {
            val row = y * bytesPerRow
            for (i in row until row + bytesPerRow) {
                if (data[i].toInt() != 0) {
                    n++
                    break
                }
            }
        }
        return n
    }

    companion object {
        fun blank(width: Int, height: Int): MonoImage = MonoImage(width, height, ByteArray(((width + 7) ushr 3) * height))

        /**
         * Converts luminance values ([gray] row-major, 0 = black … 255 = white) to 1 bit, with
         * Floyd–Steinberg dithering for photos/logos or a plain threshold for text.
         */
        fun fromGray(gray: IntArray, width: Int, height: Int, dither: Boolean, threshold: Int = 128): MonoImage {
            require(gray.size == width * height) { "gray size ${gray.size} != ${width * height}" }
            val bpr = (width + 7) ushr 3
            val out = ByteArray(bpr * height)
            if (!dither) {
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        if (gray[y * width + x] < threshold) {
                            val i = y * bpr + (x ushr 3)
                            out[i] = (out[i].toInt() or (0x80 ushr (x and 7))).toByte()
                        }
                    }
                }
                return MonoImage(width, height, out)
            }
            // Error buffers for the current and the next row (values may leave 0..255).
            var cur = IntArray(width + 2)
            var next = IntArray(width + 2)
            for (x in 0 until width) cur[x + 1] = gray[x]
            for (y in 0 until height) {
                if (y + 1 < height) {
                    val base = (y + 1) * width
                    for (x in 0 until width) next[x + 1] = gray[base + x]
                } else {
                    java.util.Arrays.fill(next, 0)
                }
                for (x in 0 until width) {
                    val old = cur[x + 1]
                    val black = old < threshold
                    if (black) {
                        val i = y * bpr + (x ushr 3)
                        out[i] = (out[i].toInt() or (0x80 ushr (x and 7))).toByte()
                    }
                    val err = old - if (black) 0 else 255
                    cur[x + 2] += err * 7 / 16
                    next[x] += err * 3 / 16
                    next[x + 1] += err * 5 / 16
                    next[x + 2] += err / 16
                }
                val t = cur
                cur = next
                next = t
            }
            return MonoImage(width, height, out)
        }
    }
}
