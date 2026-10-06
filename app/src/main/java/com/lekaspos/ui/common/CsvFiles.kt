package com.lekaspos.ui.common

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.lekaspos.R
import com.lekaspos.core.csv.CsvInput
import com.lekaspos.util.Log
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Reader
import java.nio.charset.Charset

/**
 * CSV files in and out (D-041): exports are written in the background either to a file the
 * user picks (Storage Access Framework — Downloads, Drive, a USB stick) or to the app cache and
 * shared through FileProvider (WhatsApp, e-mail). Imports come from the system file picker.
 */
object CsvFiles {

    const val MIME = "text/csv"

    /**
     * MIME types the import picker offers (spreadsheet apps and file managers disagree on CSV).
     * Excel and OpenDocument files can be picked too: the import then says how to save them as
     * CSV, where the picker used to grey them out without a word (2026-10 review).
     */
    val OPEN_TYPES = arrayOf(
        "text/csv", "text/comma-separated-values", "text/plain", "application/csv", "text/*",
        "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.oasis.opendocument.spreadsheet",
    )

    /** Writes a cache file for sharing. Blocking. */
    fun shareFile(ctx: Context, name: String): File = sharedFile(ctx, KIND_CSV, name)

    /**
     * A file in `cache/shared/<kind>/` (FileProvider path "shared") to share [name] from. Blocking.
     * Only files older than [SHARED_KEEP_MS] are deleted (2026-10 review): every share used to empty
     * the whole folder, so a receipt shared now deleted the CSV or backup another app had not read
     * yet. The shared file itself is replaced.
     */
    fun sharedFile(ctx: Context, kind: String, name: String): File {
        cleanShared(ctx)
        val dir = File(File(ctx.cacheDir, "shared"), kind).apply { mkdirs() }
        return File(dir, name).apply { delete() }
    }

    /**
     * Deletes shared files older than [SHARED_KEEP_MS]. Also at every start (Blocking): a backup
     * shared once — a whole copy of the shop's data, PIN records included — stayed in the cache until
     * the next share, for weeks (2026-10 review).
     */
    fun cleanShared(ctx: Context) {
        val root = File(ctx.cacheDir, "shared")
        val now = System.currentTimeMillis()
        val old = now - SHARED_KEEP_MS
        root.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                f.listFiles()?.forEach { if (it.lastModified() < old || it.lastModified() > now + SHARED_KEEP_MS) it.delete() }
            } else if (f.lastModified() < old) {
                f.delete() // left directly in the folder by older versions
            }
        }
    }

    const val KIND_CSV = "csv"
    const val KIND_RECEIPT = "receipt"
    const val KIND_BACKUP = "backup"
    private const val SHARED_KEEP_MS = 15L * 60_000L

    fun writer(file: File): BufferedWriter = BufferedWriter(OutputStreamWriter(file.outputStream(), Charsets.UTF_8), 64 * 1024)

    fun writer(ctx: Context, uri: Uri): BufferedWriter =
        BufferedWriter(OutputStreamWriter(openForWriting(ctx, uri), Charsets.UTF_8), 64 * 1024)

    /**
     * Opens a file the user picked to save into, emptied first ("wt"). A provider that refuses "wt" (some
     * cloud apps take only "w") gets "w": the picker has just made the file, so it is empty anyway.
     */
    fun openForWriting(ctx: Context, uri: Uri): OutputStream {
        val resolver = ctx.contentResolver
        val out = try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            Log.w("The picked file cannot be opened with \"wt\": \"w\" instead", e)
            resolver.openOutputStream(uri, "w")
        }
        return out ?: throw IllegalStateException("cannot write $uri")
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
     * A reader for an imported file, its character set judged from the whole file in one pass of
     * constant memory ([CsvInput.detect]): UTF-16 when it starts with a UTF-16 BOM (Excel's
     * "Unicode Text"), UTF-8 when (almost) all of it is valid UTF-8 — a few broken bytes are read as
     * U+FFFD and the preview names their lines — otherwise Windows-1252 (Excel's "CSV" on Windows).
     * Judged by its first 32 KB, a file with "Nescafé" on row 700 was read as UTF-8 and the name
     * imported as "Nescaf�"; and one stray byte switched a whole UTF-8 file to Windows-1252 and
     * garbled every name in it (2026-10 reviews).
     *
     * @throws CsvInput.SpreadsheetFile for an Excel (.xlsx, .xls) or OpenDocument file: it was read
     * as text and refused as "Required columns are missing".
     */
    fun reader(ctx: Context, uri: Uri): Reader {
        fun open() = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("cannot read $uri")
        val detected = open().use { CsvInput.detect(it) }
        if (detected.spreadsheet) throw CsvInput.SpreadsheetFile()
        return InputStreamReader(BufferedInputStream(open(), 64 * 1024), detected.charset)
    }

    /** The character set of a whole file read from [input] (see [reader]); [input] is read to its end. */
    fun charsetOf(input: InputStream): Charset = CsvInput.detect(input).charset
}
