package com.lekaspos.ui.common

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.lekaspos.R
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * CSV files in and out (D-041): exports are written in the background either to a file the
 * user picks (Storage Access Framework — Downloads, Drive, a USB stick) or to the app cache and
 * shared through FileProvider (WhatsApp, e-mail). Imports come from the system file picker.
 */
object CsvFiles {

    const val MIME = "text/csv"

    /** MIME types the import picker offers (spreadsheet apps and file managers disagree on CSV). */
    val OPEN_TYPES = arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/csv", "application/vnd.ms-excel", "text/*")

    /** Writes a cache file for sharing (one exported file kept at a time). */
    fun shareFile(ctx: Context, name: String): File {
        val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        return File(dir, name)
    }

    fun writer(file: File): BufferedWriter = BufferedWriter(OutputStreamWriter(file.outputStream(), Charsets.UTF_8), 64 * 1024)

    fun writer(ctx: Context, uri: Uri): BufferedWriter {
        val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("cannot write $uri")
        return BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), 64 * 1024)
    }

    fun share(a: Activity, file: File) {
        val app = a.applicationContext
        val uri = FileProvider.getUriForFile(app, app.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(MIME)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        a.startActivity(Intent.createChooser(send, a.getString(R.string.export_share)))
    }

    fun createDocumentIntent(name: String): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType(MIME)
        .putExtra(Intent.EXTRA_TITLE, name)

    fun openDocumentIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("*/*")
        .putExtra(Intent.EXTRA_MIME_TYPES, OPEN_TYPES)

    /**
     * A reader for an imported file: UTF-8 when the start of the file is valid UTF-8 (with or
     * without BOM), otherwise Windows-1252 (Excel's "CSV" on Windows saves that).
     */
    fun reader(ctx: Context, uri: Uri): Reader {
        val input = BufferedInputStream(ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("cannot read $uri"), 64 * 1024)
        input.mark(SNIFF)
        val head = ByteArray(SNIFF)
        var n = 0
        while (n < SNIFF) {
            val r = input.read(head, n, SNIFF - n)
            if (r <= 0) break
            n += r
        }
        input.reset()
        return InputStreamReader(input, if (isUtf8(head, n)) Charsets.UTF_8 else Charset.forName("windows-1252"))
    }

    /** Valid UTF-8? When the sample is full, a character cut off by its end (up to 3 bytes) is not an error. */
    internal fun isUtf8(bytes: ByteArray, n: Int, full: Boolean = n == SNIFF): Boolean {
        for (cut in 0..(if (full) minOf(3, n) else 0)) {
            try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, n - cut))
                return true
            } catch (e: CharacterCodingException) {
                // try one byte shorter
            }
        }
        return false
    }

    private const val SNIFF = 32 * 1024
}
