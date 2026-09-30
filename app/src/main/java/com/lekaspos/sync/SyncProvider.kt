package com.lekaspos.sync

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * A file in the store's sync folder. [props] are small key/values stored with it (sha256, count);
 * [created] is when the folder received it, by the folder's own clock (0 = unknown).
 */
data class RemoteFile(val name: String, val id: String, val size: Long, val props: Map<String, String> = emptyMap(), val created: Long = 0L)

/** The provider needs the user to sign in again (access revoked, password changed …). */
open class AuthNeeded(message: String) : java.io.IOException(message)

/**
 * Where the tills of a store exchange files (references/sync.md §9): a dumb shared folder. The
 * engine knows nothing about Google Drive; a USB stick or a test directory works the same.
 * Every call may fail with an IOException (offline, signed out …); the engine retries later.
 */
interface SyncProvider {
    val id: String

    /**
     * Files whose names start with [prefix]; with [since], only those created after it (by the
     * folder's clock, as in [RemoteFile.created]) — a quick listing that stays short as the store
     * grows (D-053). A provider may return more than asked, never less. [keep] sees every file as
     * the listing is read and decides whether it is returned, so a folder of many thousands of
     * files is never held in memory whole.
     */
    suspend fun list(prefix: String, since: Long? = null, keep: (RemoteFile) -> Boolean = { true }): List<RemoteFile>

    /**
     * Stores [file] as [name]. With [replace] an existing file of that name (this till's own
     * device card) is overwritten; otherwise an existing file is left as it is (same content).
     * [fresh]: this file was certainly never sent before, so the provider may skip looking for it.
     */
    suspend fun put(name: String, file: File, props: Map<String, String> = emptyMap(), replace: Boolean = false, fresh: Boolean = false): RemoteFile

    suspend fun get(remote: RemoteFile, dest: File)

    suspend fun delete(remote: RemoteFile)
}

/**
 * A plain directory as the sync folder: several test databases share one to act as several
 * tills, and a USB stick or SD card can carry segments between tills without internet.
 * Props live in a hidden side file per file.
 */
class FolderProvider(private val dir: File) : SyncProvider {

    override val id: String = "folder"

    override suspend fun list(prefix: String, since: Long?, keep: (RemoteFile) -> Boolean): List<RemoteFile> {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) && !f.name.startsWith(".") && !f.name.endsWith(TMP) }.orEmpty()
        return files.filter { since == null || it.lastModified() > since }.sortedBy { it.name }
            .map { RemoteFile(it.name, it.name, it.length(), props(it), it.lastModified()) }
            .filter(keep)
    }

    override suspend fun put(name: String, file: File, props: Map<String, String>, replace: Boolean, fresh: Boolean): RemoteFile {
        dir.mkdirs()
        val target = File(dir, name)
        if (target.exists() && !replace) return RemoteFile(name, name, target.length(), props(target))
        val tmp = File(dir, name + TMP)
        FileInputStream(file).use { i -> FileOutputStream(tmp).use { o -> i.copyTo(o, 64 * 1024); o.fd.sync() } }
        if (props.isNotEmpty()) File(dir, ".$name.props").writeText(props.entries.joinToString("\n") { "${it.key}=${it.value}" })
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw java.io.IOException("cannot write $name")
        }
        return RemoteFile(name, name, target.length(), props)
    }

    override suspend fun get(remote: RemoteFile, dest: File) {
        FileInputStream(File(dir, remote.id)).use { i -> FileOutputStream(dest).use { o -> i.copyTo(o, 64 * 1024) } }
    }

    override suspend fun delete(remote: RemoteFile) {
        File(dir, remote.id).delete()
        File(dir, ".${remote.id}.props").delete()
    }

    private fun props(f: File): Map<String, String> {
        val p = File(dir, ".${f.name}.props")
        if (!p.exists()) return emptyMap()
        return p.readLines().mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    }

    private companion object {
        const val TMP = ".part"
    }
}
