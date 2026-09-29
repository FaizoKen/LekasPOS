package com.lekaspos.sync.drive

import android.util.JsonWriter
import com.lekaspos.data.sync.SegmentCodec
import com.lekaspos.sync.RemoteFile
import com.lekaspos.sync.SyncProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Google Drive's hidden app folder as the store's sync folder (references/sync.md §10, D-014):
 * REST v3 over HttpURLConnection, scope `drive.appdata` only — the files are invisible in the
 * user's Drive and to other apps. [token] gives a current OAuth access token (it is fetched
 * again after a 401). Small files go up as one multipart request, larger ones resumably.
 */
class DriveProvider(private val token: suspend (refresh: Boolean) -> String) : SyncProvider {

    override val id: String = com.lekaspos.sync.SyncProviders.GDRIVE

    class HttpError(val code: Int, message: String) : IOException("Drive HTTP $code: $message")

    override suspend fun list(prefix: String): List<RemoteFile> {
        val out = ArrayList<RemoteFile>()
        var page: String? = null
        do {
            val q = "name contains '${prefix.replace("'", "\\'")}' and trashed = false"
            val url = "$API/files?spaces=appDataFolder&pageSize=1000&fields=${enc("nextPageToken,files(id,name,size,appProperties)")}" +
                "&q=${enc(q)}" + (page?.let { "&pageToken=${enc(it)}" } ?: "")
            val json = request("GET", url) { null }.let { String(it, Charsets.UTF_8) }
            @Suppress("UNCHECKED_CAST")
            val m = SegmentCodec.parse(json) as? Map<String, Any?> ?: throw IOException("bad Drive listing")
            @Suppress("UNCHECKED_CAST")
            for (f in (m["files"] as? List<Any?>).orEmpty()) {
                val file = f as? Map<String, Any?> ?: continue
                val name = file["name"] as? String ?: continue
                if (!name.startsWith(prefix)) continue // "contains" matches word prefixes; keep exact prefixes only
                val props = (file["appProperties"] as? Map<String, Any?>).orEmpty().mapValues { it.value.toString() }
                out.add(RemoteFile(name, file["id"] as String, (file["size"] as? String)?.toLongOrNull() ?: 0L, props))
            }
            page = m["nextPageToken"] as? String
        } while (page != null)
        return out.sortedBy { it.name }
    }

    override suspend fun put(name: String, file: File, props: Map<String, String>, replace: Boolean): RemoteFile {
        val existing = list(name).firstOrNull { it.name == name }
        if (existing != null) {
            if (!replace) return existing // uploaded before a crash: never make a second copy
            request("PATCH", "$UPLOAD/files/${existing.id}?uploadType=media") { c ->
                c.setRequestProperty("Content-Type", "application/octet-stream")
                FileBody(file)
            }
            return existing.copy(size = file.length())
        }
        val meta = metadata(name, props)
        val id = if (file.length() <= MULTIPART_MAX) multipart(meta, file) else resumable(meta, file)
        return RemoteFile(name, id, file.length(), props)
    }

    override suspend fun get(remote: RemoteFile, dest: File) {
        withContext(Dispatchers.IO) {
            val c = open("GET", "$API/files/${remote.id}?alt=media", token(false))
            try {
                if (c.responseCode == 401) {
                    c.disconnect()
                    val retry = open("GET", "$API/files/${remote.id}?alt=media", token(true))
                    try {
                        stream(retry, dest)
                    } finally {
                        retry.disconnect()
                    }
                } else {
                    stream(c, dest)
                }
            } finally {
                c.disconnect()
            }
        }
    }

    override suspend fun delete(remote: RemoteFile) {
        try {
            request("DELETE", "$API/files/${remote.id}") { null }
        } catch (e: HttpError) {
            if (e.code != 404) throw e
        }
    }

    /** The signed-in account's e-mail (shown in the sync settings). */
    suspend fun accountEmail(): String? {
        val json = String(request("GET", "$API/about?fields=${enc("user(emailAddress)")}") { null }, Charsets.UTF_8)
        @Suppress("UNCHECKED_CAST")
        val user = (SegmentCodec.parse(json) as? Map<String, Any?>)?.get("user") as? Map<String, Any?>
        return user?.get("emailAddress") as? String
    }

    // ------------------------------------------------------------------ HTTP

    private fun metadata(name: String, props: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        JsonWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { w ->
            w.beginObject()
            w.name("name").value(name)
            w.name("parents").beginArray().value("appDataFolder").endArray()
            if (props.isNotEmpty()) {
                w.name("appProperties").beginObject()
                for ((k, v) in props) w.name(k).value(v)
                w.endObject()
            }
            w.endObject()
        }
        return out.toByteArray()
    }

    private suspend fun multipart(meta: ByteArray, file: File): String {
        val boundary = "lekas${System.nanoTime()}"
        val head = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n").toByteArray() + meta +
            ("\r\n--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n").toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val body = request("POST", "$UPLOAD/files?uploadType=multipart&fields=id") { c ->
            c.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            MultipartBody(head, file, tail)
        }
        return idOf(body)
    }

    /** A resumable upload: open a session, then send the bytes; a broken transfer resumes from what Drive has. */
    private suspend fun resumable(meta: ByteArray, file: File): String = withContext(Dispatchers.IO) {
        val start = open("POST", "$UPLOAD/files?uploadType=resumable&fields=id", token(false))
        start.doOutput = true
        start.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        start.setRequestProperty("X-Upload-Content-Length", file.length().toString())
        start.outputStream.use { it.write(meta) }
        val code = start.responseCode
        val session = start.getHeaderField("Location")
        start.disconnect()
        if (code !in 200..299 || session == null) throw HttpError(code, "cannot start an upload")
        var offset = 0L
        repeat(RESUME_TRIES) {
            val c = open("PUT", session, token(false))
            try {
                c.doOutput = true
                val len = file.length() - offset
                c.setFixedLengthStreamingMode(len)
                c.setRequestProperty("Content-Range", "bytes $offset-${file.length() - 1}/${file.length()}")
                FileInputStream(file).use { i ->
                    i.skip(offset)
                    c.outputStream.use { o -> i.copyTo(o, 64 * 1024) }
                }
                if (c.responseCode in 200..299) return@withContext idOf(c.inputStream.use { it.readBytes() })
            } catch (e: IOException) {
                // fall through: ask Drive how much arrived, then continue from there
            } finally {
                c.disconnect()
            }
            offset = uploadedBytes(session, file.length())
        }
        throw IOException("upload of ${file.name} did not finish")
    }

    private suspend fun uploadedBytes(session: String, total: Long): Long {
        val c = open("PUT", session, token(false))
        try {
            c.doOutput = true
            c.setFixedLengthStreamingMode(0)
            c.setRequestProperty("Content-Range", "bytes */$total")
            c.outputStream.close()
            if (c.responseCode == 308) {
                val range = c.getHeaderField("Range") ?: return 0L // "bytes=0-1234"
                return range.substringAfterLast('-').toLongOrNull()?.plus(1L) ?: 0L
            }
            return 0L
        } finally {
            c.disconnect()
        }
    }

    private interface Body {
        fun length(): Long
        fun write(out: java.io.OutputStream)
    }

    private class FileBody(val file: File) : Body {
        override fun length() = file.length()
        override fun write(out: java.io.OutputStream) = FileInputStream(file).use { it.copyTo(out, 64 * 1024); Unit }
    }

    private class MultipartBody(val head: ByteArray, val file: File, val tail: ByteArray) : Body {
        override fun length() = head.size + file.length() + tail.size
        override fun write(out: java.io.OutputStream) {
            out.write(head)
            FileInputStream(file).use { it.copyTo(out, 64 * 1024) }
            out.write(tail)
        }
    }

    /** One request; retried once with a fresh token after a 401. */
    private suspend fun request(method: String, url: String, prepare: (HttpURLConnection) -> Body?): ByteArray = withContext(Dispatchers.IO) {
        var refresh = false
        while (true) {
            val c = open(method, url, token(refresh))
            try {
                val body = prepare(c)
                if (body != null) {
                    c.doOutput = true
                    c.setFixedLengthStreamingMode(body.length())
                    c.outputStream.use { body.write(it) }
                }
                val code = c.responseCode
                if (code == 401 && !refresh) {
                    refresh = true
                    continue
                }
                if (code !in 200..299) throw HttpError(code, c.errorStream?.use { String(it.readBytes().take(300).toByteArray()) } ?: "")
                return@withContext c.inputStream.use { it.readBytes() }
            } finally {
                c.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        ByteArray(0)
    }

    private fun open(method: String, url: String, token: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        if (method == "PATCH") {
            // HttpURLConnection has no PATCH: Google APIs accept the override header.
            c.requestMethod = "POST"
            c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        } else {
            c.requestMethod = method
        }
        c.connectTimeout = 30_000
        c.readTimeout = 60_000
        c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    private fun stream(c: HttpURLConnection, dest: File) {
        val code = c.responseCode
        if (code !in 200..299) throw HttpError(code, "download failed")
        val input: InputStream = if (c.contentEncoding == "gzip") java.util.zip.GZIPInputStream(c.inputStream) else c.inputStream
        input.use { i -> FileOutputStream(dest).use { o -> i.copyTo(o, 64 * 1024) } }
    }

    private fun idOf(body: ByteArray): String {
        @Suppress("UNCHECKED_CAST")
        val m = SegmentCodec.parse(String(body, Charsets.UTF_8)) as? Map<String, Any?>
        return m?.get("id") as? String ?: throw IOException("Drive did not return a file id")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val MULTIPART_MAX = 5L * 1024L * 1024L
        private const val RESUME_TRIES = 5
        const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    }
}
