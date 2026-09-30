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
 *
 * Speed (D-053): connections are kept open and reused between requests (a successful response
 * is read to the end and closed, never disconnected); this till's own files are remembered by
 * id, so replacing its device card is one request; a first upload does not look for the file
 * first; and a listing can ask only for files created after a time.
 */
class DriveProvider(private val token: suspend (refresh: Boolean) -> String) : SyncProvider {

    override val id: String = com.lekaspos.sync.SyncProviders.GDRIVE

    class HttpError(val code: Int, message: String) : IOException("Drive HTTP $code: $message")

    /**
     * Drive's `name contains` matches word prefixes, so the query uses only the first word of
     * [prefix] ("seg", "dev", "store"); the exact prefix is checked here.
     */
    override suspend fun list(prefix: String, since: Long?, keep: (RemoteFile) -> Boolean): List<RemoteFile> =
        query(
            "name contains '${quote(prefix.substringBefore('-'))}' and trashed = false" +
                (since?.let { " and createdTime > '${rfc3339(it)}'" } ?: ""),
        ) { it.name.startsWith(prefix) && keep(it) }
            .sortedBy { it.name }

    /** The file called exactly [name], if any. */
    private suspend fun find(name: String): RemoteFile? =
        query("name = '${quote(name)}' and trashed = false") { it.name == name }.firstOrNull()

    /** Every page of the listing [q]; only files [keep] accepts are kept (page by page). */
    private suspend fun query(q: String, keep: (RemoteFile) -> Boolean): List<RemoteFile> {
        val out = ArrayList<RemoteFile>()
        var page: String? = null
        do {
            val url = "$API/files?spaces=appDataFolder&pageSize=1000&fields=${enc("nextPageToken,files(id,name,size,appProperties,createdTime)")}" +
                "&q=${enc(q)}" + (page?.let { "&pageToken=${enc(it)}" } ?: "")
            val json = request("GET", url) { null }.let { String(it, Charsets.UTF_8) }
            @Suppress("UNCHECKED_CAST")
            val m = SegmentCodec.parse(json) as? Map<String, Any?> ?: throw IOException("bad Drive listing")
            @Suppress("UNCHECKED_CAST")
            for (f in (m["files"] as? List<Any?>).orEmpty()) {
                val file = f as? Map<String, Any?> ?: continue
                val name = file["name"] as? String ?: continue
                val props = (file["appProperties"] as? Map<String, Any?>).orEmpty().mapValues { it.value.toString() }
                val created = (file["createdTime"] as? String)?.let { parseTime(it) } ?: 0L
                val rf = RemoteFile(name, file["id"] as String, (file["size"] as? String)?.toLongOrNull() ?: 0L, props, created)
                if (keep(rf)) out.add(rf)
            }
            page = m["nextPageToken"] as? String
        } while (page != null)
        return out
    }

    /** Escapes a string literal for a Drive query. */
    private fun quote(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    override suspend fun put(name: String, file: File, props: Map<String, String>, replace: Boolean, fresh: Boolean): RemoteFile {
        if (replace) {
            val id = knownIds[name]
            if (id != null) {
                try {
                    patch(id, file)
                    return RemoteFile(name, id, file.length(), props)
                } catch (e: HttpError) {
                    if (e.code != 404) throw e
                    knownIds.remove(name) // deleted meanwhile: look it up again below
                }
            }
        }
        val existing = if (fresh) null else find(name)
        if (existing != null) {
            if (!replace) return existing // uploaded before a crash: never make a second copy
            patch(existing.id, file)
            knownIds[name] = existing.id
            return existing.copy(size = file.length())
        }
        val meta = metadata(name, props)
        val id = if (file.length() <= MULTIPART_MAX) multipart(meta, file) else resumable(meta, file)
        if (replace) knownIds[name] = id
        return RemoteFile(name, id, file.length(), props)
    }

    private suspend fun patch(id: String, file: File) {
        request("PATCH", "$UPLOAD/files/$id?uploadType=media") { c ->
            c.setRequestProperty("Content-Type", "application/octet-stream")
            FileBody(file)
        }
    }

    override suspend fun get(remote: RemoteFile, dest: File) {
        withContext(Dispatchers.IO) {
            val url = "$API/files/${remote.id}?alt=media"
            val c = open("GET", url, token(false))
            if (keepAlive(c) { c.responseCode } == 401) {
                c.disconnect()
                val retry = open("GET", url, token(true))
                keepAlive(retry) { stream(retry, dest) }
            } else {
                keepAlive(c) { stream(c, dest) }
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

    /**
     * One request; retried once with a fresh token after a 401, and once on a new connection when
     * a kept-open one turns out to be dead (a router or carrier dropped it while idle, D-053).
     * Sending a file twice is harmless: the copies are identical and the reader takes either.
     */
    private suspend fun request(method: String, url: String, prepare: (HttpURLConnection) -> Body?): ByteArray = withContext(Dispatchers.IO) {
        var refresh = false
        var reconnected = false
        while (true) {
            val c = open(method, url, token(refresh))
            val result = try {
                exchange(c, refresh, prepare)
            } catch (e: IOException) {
                // Not for an answer from Drive, nor when the network itself is gone or too slow.
                if (reconnected || e is HttpError || e is java.net.SocketTimeoutException ||
                    e is java.net.UnknownHostException || e is java.net.ConnectException
                ) {
                    throw e
                }
                reconnected = true
                continue
            }
            if (result != null) return@withContext result
            refresh = true
        }
        @Suppress("UNREACHABLE_CODE")
        ByteArray(0)
    }

    /** Sends [c]'s request; null when the token had expired (401, first try). */
    private fun exchange(c: HttpURLConnection, refresh: Boolean, prepare: (HttpURLConnection) -> Body?): ByteArray? =
        keepAlive(c) {
            val body = prepare(c)
            if (body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.length())
                c.outputStream.use { body.write(it) }
            }
            val code = c.responseCode
            if (code == 401 && !refresh) {
                c.errorStream?.use { it.readBytes() }
                null // an expired token: once more with a fresh one
            } else {
                if (code !in 200..299) throw HttpError(code, c.errorStream?.use { String(it.readBytes().take(300).toByteArray()) } ?: "")
                c.inputStream.use { it.readBytes() }
            }
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
        // Short enough that a dead network is reported in seconds, not minutes.
        c.connectTimeout = CONNECT_TIMEOUT_MS
        c.readTimeout = READ_TIMEOUT_MS
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

    /**
     * Runs [block] on [c]; a response read to the end leaves the connection in the pool for the
     * next request. A failed exchange drops it (it may be half-read or broken); failures are rare.
     */
    private inline fun <T> keepAlive(c: HttpURLConnection, block: () -> T): T =
        try {
            block()
        } catch (e: IOException) {
            c.disconnect()
            throw e
        }

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val MULTIPART_MAX = 5L * 1024L * 1024L
        private const val RESUME_TRIES = 5
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        /** This process's own files by name (its device card): replaced without a lookup. */
        private val knownIds = java.util.concurrent.ConcurrentHashMap<String, String>()

        /** Drive times are RFC 3339 in UTC, e.g. 2026-09-30T12:34:56.789Z (no java.time on API 21). */
        internal fun parseTime(s: String): Long? = try {
            val base = utc().parse(s.substring(0, 19))?.time
            val millis = s.substringAfter('.', "").takeWhile { it.isDigit() }.padEnd(3, '0').take(3).toLong()
            base?.plus(millis)
        } catch (e: Exception) {
            null
        }

        internal fun rfc3339(ms: Long): String = utc().format(java.util.Date(ms))

        private fun utc() = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    }
}
