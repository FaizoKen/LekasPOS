package com.lekaspos.sync.drive

import android.util.JsonWriter
import com.lekaspos.data.sync.SegmentCodec
import com.lekaspos.sync.AuthNeeded
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
 *
 * With a [FILE_SCOPE] token it also writes the daily sales report into a visible folder of the
 * user's Drive ([folder], [putInFolder], D-065); that scope sees only the files this app made.
 */
class DriveProvider(private val token: suspend (refresh: Boolean) -> String) : SyncProvider {

    override val id: String = com.lekaspos.sync.SyncProviders.GDRIVE

    class HttpError(val code: Int, message: String, body: String = message) : IOException("Drive HTTP $code: $message") {
        /**
         * Google's reason ("storageQuotaExceeded", "userRateLimitExceeded" …) when the answer names
         * one: read from the whole answer (its long "message" comes first and was cut off).
         */
        val reason: String? = REASON.find(body)?.groupValues?.get(1)

        companion object {
            private val REASON = Regex(""""reason"\s*:\s*"([A-Za-z]+)"""")

            /** From an error answer's body: the reason from all of it, the message from its start. */
            fun of(code: Int, body: String): HttpError = HttpError(code, body.take(300), body)
        }
    }

    /** An error answer's body, at most 8 KB. */
    private fun errorBody(c: HttpURLConnection): String = try {
        c.errorStream?.use { s ->
            val bytes = s.readBytes()
            String(if (bytes.size > MAX_ERROR_BODY) bytes.copyOf(MAX_ERROR_BODY) else bytes, Charsets.UTF_8)
        }.orEmpty()
    } catch (e: IOException) {
        ""
    }

    @Volatile
    private var offset: Long? = null

    override val clockOffset: Long? get() = offset

    /** Google's clock (the answer's `Date`, to the second) against this phone's. */
    private fun noteClock(c: HttpURLConnection) {
        val server = c.date
        if (server > 0L) offset = server - System.currentTimeMillis()
    }

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

    /** Every file called exactly [name] (Drive allows several). */
    private suspend fun find(name: String): List<RemoteFile> =
        query("name = '${quote(name)}' and trashed = false") { it.name == name }

    /** Every page of the listing [q] in [spaces]; only files [keep] accepts are kept (page by page). */
    private suspend fun query(q: String, spaces: String = APP_DATA, keep: (RemoteFile) -> Boolean): List<RemoteFile> {
        val out = ArrayList<RemoteFile>()
        var page: String? = null
        do {
            // prettyPrint=false and a gzip answer (see [open]): a listing is 3–4 times smaller.
            val url = "$API/files?spaces=$spaces&pageSize=1000&prettyPrint=false" +
                "&fields=${enc("nextPageToken,files(id,name,size,appProperties,createdTime)")}" +
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
        val copies = if (fresh) emptyList() else find(name)
        val existing = copies.firstOrNull()
        if (existing != null) {
            if (!replace) return existing // uploaded before a crash: never make a second copy
            // This till's own file (its card) twice — a create whose answer was lost: one stays (2026-10 review).
            for (extra in copies.drop(1)) delete(extra)
            patch(existing.id, file)
            knownIds[name] = existing.id
            return existing.copy(size = file.length())
        }
        val meta = metadata(name, props)
        val id = try {
            create(meta, file)
        } catch (e: AnswerLost) {
            // It may have been created (2026-10 review): looked up before it is sent once more.
            find(name).firstOrNull()?.id ?: try {
                create(meta, file)
            } catch (again: AnswerLost) {
                throw again.io
            }
        }
        if (replace) knownIds[name] = id
        return RemoteFile(name, id, file.length(), props)
    }

    private suspend fun create(meta: ByteArray, file: File): String =
        if (file.length() <= MULTIPART_MAX) multipart(meta, file) else resumable(meta, file)

    private suspend fun patch(id: String, file: File, mime: String = OCTET) {
        request("PATCH", "$UPLOAD/files/$id?uploadType=media") { c ->
            c.setRequestProperty("Content-Type", mime)
            FileBody(file)
        }
    }

    override suspend fun get(remote: RemoteFile, dest: File) {
        withContext(Dispatchers.IO) {
            val url = "$API/files/${remote.id}?alt=media"
            var refresh = false
            while (true) {
                val c = open("GET", url, token(refresh))
                val code = keepAlive(c) { c.responseCode }
                noteClock(c)
                if (code == 401) {
                    c.disconnect()
                    if (refresh) throw DriveAuth.SignInNeeded() // a fresh token refused too (2026-10 review)
                    refresh = true
                    continue
                }
                keepAlive(c) { stream(c, dest) }
                break
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

    // ------------------------------------------------------------------ visible files (the daily report, D-065)

    /**
     * The id of the folder [name] at the top of My Drive that this app made, made now when there is
     * none (also when the user deleted it, or moved it to the bin). Two made at once: the oldest is used.
     */
    internal suspend fun folder(name: String): String {
        val q = "mimeType = '$FOLDER_MIME' and name = '${quote(name)}' and 'root' in parents and trashed = false"
        suspend fun oldest(): String? = query(q, DRIVE) { true }.minByOrNull { it.created }?.id
        oldest()?.let { return it }
        val meta = json { w ->
            w.name("name").value(name)
            w.name("mimeType").value(FOLDER_MIME)
            w.name("parents").beginArray().value("root").endArray()
        }
        return try {
            createFolder(meta)
        } catch (e: AnswerLost) {
            // It may have been made (like [put]): looked up before it is sent once more.
            oldest() ?: try {
                createFolder(meta)
            } catch (again: AnswerLost) {
                throw again.io
            }
        }
    }

    private suspend fun createFolder(meta: ByteArray): String = idOf(
        request("POST", "$API/files?fields=id") { c ->
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            BytesBody(meta)
        },
    )

    /**
     * Writes [file] as [name] into [folder]: the file of that name this app made there gets the new
     * content (it keeps its place and link in Drive); a second copy (a create whose answer was lost)
     * is removed.
     */
    internal suspend fun putInFolder(folder: String, name: String, file: File, mime: String) {
        val q = "name = '${quote(name)}' and '${quote(folder)}' in parents and trashed = false"
        val copies = query(q, DRIVE) { it.name == name }.sortedBy { it.created }
        val existing = copies.firstOrNull()
        if (existing != null) {
            for (extra in copies.drop(1)) delete(extra)
            patch(existing.id, file, mime)
            return
        }
        val meta = json { w ->
            w.name("name").value(name)
            w.name("mimeType").value(mime)
            w.name("parents").beginArray().value(folder).endArray()
        }
        try {
            multipart(meta, file, mime)
        } catch (e: AnswerLost) {
            if (query(q, DRIVE) { it.name == name }.isEmpty()) {
                try {
                    multipart(meta, file, mime)
                } catch (again: AnswerLost) {
                    throw again.io
                }
            }
        }
    }

    private fun json(fields: (JsonWriter) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        JsonWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { w ->
            w.beginObject()
            fields(w)
            w.endObject()
        }
        return out.toByteArray()
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

    private suspend fun multipart(meta: ByteArray, file: File, mime: String = OCTET): String {
        val boundary = "lekas${System.nanoTime()}"
        val head = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n").toByteArray() + meta +
            ("\r\n--$boundary\r\nContent-Type: $mime\r\n\r\n").toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val body = request("POST", "$UPLOAD/files?uploadType=multipart&fields=id") { c ->
            c.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            MultipartBody(head, file, tail)
        }
        return idOf(body)
    }

    /** A resumable upload: open a session, then send the bytes; a broken transfer resumes from what Drive has. */
    private suspend fun resumable(meta: ByteArray, file: File): String = withContext(Dispatchers.IO) {
        val session = startUpload(meta, file.length())
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

    /** Opens a resumable upload session; an expired token is renewed once, like every other request. */
    private suspend fun startUpload(meta: ByteArray, length: Long): String {
        var refresh = false
        while (true) {
            val start = open("POST", "$UPLOAD/files?uploadType=resumable&fields=id", token(refresh))
            val (code, session) = try {
                start.doOutput = true
                start.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                start.setRequestProperty("X-Upload-Content-Length", length.toString())
                start.outputStream.use { it.write(meta) }
                noteClock(start)
                val code = start.responseCode
                // A refusal says why (a full Drive): the resumable path lost it (2026-10 review).
                if (code !in 200..299 && code != 401) throw HttpError.of(code, errorBody(start))
                code to start.getHeaderField("Location")
            } finally {
                start.disconnect()
            }
            if (code == 401) {
                if (refresh) throw DriveAuth.SignInNeeded()
                refresh = true
                continue
            }
            if (code !in 200..299 || session == null) throw HttpError(code, "cannot start an upload")
            return session
        }
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

    private class BytesBody(val bytes: ByteArray) : Body {
        override fun length() = bytes.size.toLong()
        override fun write(out: java.io.OutputStream) = out.write(bytes)
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
     * a kept-open one turns out to be dead (a router or carrier dropped it while idle, D-053) —
     * but not a POST whose answer was lost after it went out whole ([AnswerLost], 2026-10 review):
     * it may have created the file, and a second one would be a duplicate.
     */
    private suspend fun request(method: String, url: String, prepare: (HttpURLConnection) -> Body?): ByteArray = withContext(Dispatchers.IO) {
        var refresh = false
        var reconnected = false
        while (true) {
            val c = open(method, url, token(refresh))
            val result = try {
                exchange(c, refresh, prepare)
            } catch (e: IOException) {
                if (e is AnswerLost && method == "POST") throw e
                val cause = (e as? AnswerLost)?.io ?: e
                // Not for an answer from Drive, nor when the network itself is gone or too slow.
                if (reconnected || cause is HttpError || cause is AuthNeeded ||
                    cause is java.net.SocketTimeoutException || cause is java.net.UnknownHostException ||
                    cause is java.net.ConnectException
                ) {
                    throw cause
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

    /**
     * The request went out whole but its answer was lost: Drive may have done it (2026-10 review).
     * A POST that creates a file is not simply sent again: [put] looks the name up first.
     */
    private class AnswerLost(val io: IOException) : IOException(io.message, io)

    /** Sends [c]'s request; null when the token had expired (401, first try). */
    private fun exchange(c: HttpURLConnection, refresh: Boolean, prepare: (HttpURLConnection) -> Body?): ByteArray? =
        keepAlive(c) {
            val body = prepare(c)
            if (body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.length())
                c.outputStream.use { body.write(it) }
            }
            try {
                answer(c, refresh)
            } catch (e: IOException) {
                if (body == null || e is HttpError || e is AuthNeeded) throw e
                throw AnswerLost(e)
            }
        }

    private fun answer(c: HttpURLConnection, refresh: Boolean): ByteArray? {
        val code = c.responseCode
        noteClock(c)
        if (code == 401) {
            c.errorStream?.use { it.readBytes() }
            // A fresh token refused too: access was withdrawn, the owner must sign in (2026-10 review).
            if (refresh) throw DriveAuth.SignInNeeded()
            return null // an expired token: once more with a fresh one
        }
        if (code !in 200..299) throw HttpError.of(code, errorBody(c))
        return c.inputStream.use { it.readBytes() }
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
        // Google compresses its answers only for a User-Agent that says "gzip"; Android's
        // HttpURLConnection asks for gzip and unpacks it by itself. The daily whole-folder listing
        // grows with every file ever sent (2026-10 review: ~37 MB a day per till after a year).
        c.setRequestProperty("User-Agent", "LekasPOS/${com.lekaspos.BuildConfig.VERSION_NAME} (gzip)")
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
        private const val MAX_ERROR_BODY = 8192
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

        /** Files this app made, visible in the user's Drive (the daily report, D-065); nothing else of theirs. */
        const val FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

        private const val APP_DATA = "appDataFolder"
        private const val DRIVE = "drive"
        private const val OCTET = "application/octet-stream"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    }
}
