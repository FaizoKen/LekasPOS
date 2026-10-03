package com.lekaspos.data.backup

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * A folder outside the app's own storage where the automatic backup is copied every day
 * (D-048): an SD card, a USB drive, or a folder the owner picked. It survives uninstalling the
 * app, and — kept apart from the phone — losing it. Only the daily copies' own names
 * (`lekaspos-<date>-<time>.lekasbak`) are ever listed or deleted.
 */
interface BackupFolder {

    class Item(val name: String, val id: String, val modified: Long)

    /** Writes a new file [name] (throws when the folder is gone, e.g. the card was removed). */
    fun write(name: String, body: (OutputStream) -> Unit)

    /** The app's backup files in the folder. */
    fun list(): List<Item>

    fun delete(item: Item)

    companion object {
        const val PREFIX = "lekaspos-"

        /** `lekaspos-2026-10-03-0915.lekasbak`; a folder app may add " (1)" when the name is taken. */
        private val DAILY = Regex("""lekaspos-\d{4}-\d{2}-\d{2}-\d{4}( \(\d+\))?\.lekasbak""")

        /**
         * A daily copy written by this feature. Backups the owner saved by hand into the same
         * folder ("lekaspos-backup-<date>.lekasbak") are not: they sorted above every daily copy by
         * name, so the pruning kept them and deleted the daily ones, then the owner's oldest saves
         * (2026-10 review).
         */
        fun isOurs(name: String) = DAILY.matches(name)
    }
}

/** A plain directory (tests; also any path the app can write). */
class FileBackupFolder(private val dir: File) : BackupFolder {

    override fun write(name: String, body: (OutputStream) -> Unit) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("folder not available: $dir")
        val part = File(dir, "$name.part")
        try {
            FileOutputStream(part).use { out ->
                body(out)
                out.fd.sync()
            }
            if (!part.renameTo(File(dir, name))) throw IOException("cannot finish $name")
        } finally {
            part.delete()
        }
    }

    override fun list(): List<BackupFolder.Item> =
        dir.listFiles { f -> f.isFile && BackupFolder.isOurs(f.name) }.orEmpty()
            .map { BackupFolder.Item(it.name, it.name, it.lastModified()) }

    override fun delete(item: BackupFolder.Item) {
        File(dir, item.id).delete()
    }
}

/** A folder picked with the system's folder picker (ACTION_OPEN_DOCUMENT_TREE, kept permission). */
class TreeBackupFolder(private val resolver: ContentResolver, private val tree: Uri) : BackupFolder {

    private val treeId: String get() = DocumentsContract.getTreeDocumentId(tree)

    override fun write(name: String, body: (OutputStream) -> Unit) {
        val dir = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)
        val doc = DocumentsContract.createDocument(resolver, dir, MIME, name)
            ?: throw IOException("cannot create $name")
        try {
            // On the card itself (fsync) and the whole of it (its size read back): a card or drive pulled
            // right after "Copied" left a cut-short copy that counted as a backup (2026-10 review).
            val fd = try {
                resolver.openFileDescriptor(doc, "w")
            } catch (e: java.io.FileNotFoundException) {
                null // a provider without file descriptors (a cloud folder): its own stream
            }
            var written = 0L
            if (fd != null) {
                fd.use {
                    val out = Counting(FileOutputStream(it.fileDescriptor))
                    body(out)
                    out.flush()
                    try {
                        it.fileDescriptor.sync()
                    } catch (e: java.io.SyncFailedException) {
                        // A pipe, not a file: nothing more to do here.
                    }
                    written = out.count
                }
            } else {
                val out = Counting(resolver.openOutputStream(doc, "w") ?: throw IOException("cannot write $name"))
                out.use(body)
                written = out.count
            }
            // Read back on the phone's own storage, SD cards and USB drives only: cloud folders may
            // report the size only once uploaded.
            val stored = if (tree.authority == LOCAL_STORAGE) size(doc) else null
            if (stored != null && stored != written) throw IOException("$name holds $stored of $written bytes")
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) } // no half-written backup left behind
            throw e
        }
    }

    /** The document's size as its provider reports it, or null when it does not say. */
    private fun size(doc: android.net.Uri): Long? =
        resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    /** Counts what goes through. */
    private class Counting(out: OutputStream) : java.io.FilterOutputStream(out) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }

    override fun list(): List<BackupFolder.Item> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = ArrayList<BackupFolder.Item>()
        resolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (!BackupFolder.isOurs(name)) continue
                out.add(BackupFolder.Item(name, c.getString(0), if (c.isNull(2)) 0L else c.getLong(2)))
            }
        } ?: throw IOException("folder not available")
        return out
    }

    override fun delete(item: BackupFolder.Item) {
        DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, item.id))
    }

    private companion object {
        const val MIME = "application/octet-stream"
        const val LOCAL_STORAGE = "com.android.externalstorage.documents"
    }
}
