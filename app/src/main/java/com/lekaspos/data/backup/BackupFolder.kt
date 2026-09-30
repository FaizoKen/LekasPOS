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
 * app, and — kept apart from the phone — losing it. Only files named `lekaspos-*.lekasbak` are
 * ever listed or deleted.
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

        fun isOurs(name: String) = name.startsWith(PREFIX) && name.endsWith(BackupFiles.EXT)
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
            val out = resolver.openOutputStream(doc, "w") ?: throw IOException("cannot write $name")
            out.use(body)
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) } // no half-written backup left behind
            throw e
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
    }
}
