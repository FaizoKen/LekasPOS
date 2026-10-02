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
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.CharBuffer
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

    /** Writes a cache file for sharing. Blocking. */
    fun shareFile(ctx: Context, name: String): File = sharedFile(ctx, KIND_CSV, name)

    /**
     * A file in `cache/shared/<kind>/` (FileProvider path "shared") to share [name] from. Blocking.
     * Only files older than [SHARED_KEEP_MS] are deleted (2026-10 review): every share used to empty
     * the whole folder, so a receipt shared now deleted the CSV or backup another app had not read
     * yet. The shared file itself is replaced.
     */
    fun sharedFile(ctx: Context, kind: String, name: String): File {
        val root = File(ctx.cacheDir, "shared")
        val old = System.currentTimeMillis() - SHARED_KEEP_MS
        root.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                f.listFiles()?.forEach { if (it.lastModified() < old) it.delete() }
            } else if (f.lastModified() < old) {
                f.delete() // left directly in the folder by older versions
            }
        }
        val dir = File(root, kind).apply { mkdirs() }
        return File(dir, name).apply { delete() }
    }

    const val KIND_CSV = "csv"
    const val KIND_RECEIPT = "receipt"
    const val KIND_BACKUP = "backup"
    private const val SHARED_KEEP_MS = 15L * 60_000L

    fun writer(file: File): BufferedWriter = BufferedWriter(OutputStreamWriter(file.outputStream(), Charsets.UTF_8), 64 * 1024)

    fun writer(ctx: Context, uri: Uri): BufferedWriter {
        val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("cannot write $uri")
        return BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), 64 * 1024)
    }

    fun share(a: Activity, file: File, mime: String = MIME) {
        val app = a.applicationContext
        val uri = FileProvider.getUriForFile(app, app.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
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
     * A reader for an imported file: UTF-16 when it starts with a UTF-16 BOM (Excel's "Unicode
     * Text"), UTF-8 when the whole file is valid UTF-8 (with or without BOM), otherwise
     * Windows-1252 (Excel's "CSV" on Windows saves that). The whole file is checked, in one pass
     * of constant memory: judged by its first 32 KB, a file with "Nescafé" on row 700 was read as
     * UTF-8 and the name imported as "Nescaf�" on every till (2026-10 review).
     */
    fun reader(ctx: Context, uri: Uri): Reader {
        fun open() = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("cannot read $uri")
        val charset = open().use { charsetOf(it) }
        return InputStreamReader(BufferedInputStream(open(), 64 * 1024), charset)
    }

    /** The character set of a whole file read from [input] (see [reader]); [input] is read to its end. */
    fun charsetOf(input: InputStream): Charset {
        val buf = ByteBuffer.allocate(64 * 1024)
        var read = input.read(buf.array(), 0, buf.capacity())
        if (read <= 0) return Charsets.UTF_8
        val a = buf.get(0).toInt() and 0xFF
        val b = if (read > 1) buf.get(1).toInt() and 0xFF else -1
        if (a == 0xFF && b == 0xFE) return Charsets.UTF_16LE // the CSV reader skips the BOM
        if (a == 0xFE && b == 0xFF) return Charsets.UTF_16BE
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val out = CharBuffer.allocate(buf.capacity()) // a byte never makes more than one character
        buf.position(read)
        while (read >= 0) {
            buf.flip()
            if (decoder.decode(buf, out, false).isError) return WINDOWS_1252
            out.clear()
            buf.compact() // a character cut off by the end of this chunk is finished by the next one
            read = input.read(buf.array(), buf.position(), buf.remaining())
            if (read > 0) buf.position(buf.position() + read)
        }
        buf.flip()
        if (decoder.decode(buf, out, true).isError || decoder.flush(out).isError) return WINDOWS_1252
        return Charsets.UTF_8
    }

    private val WINDOWS_1252: Charset get() = Charset.forName("windows-1252")
}
