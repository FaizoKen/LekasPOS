package com.lekaspos.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.util.JsonReader
import android.util.JsonToken
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.lekaspos.BuildConfig
import com.lekaspos.core.update.Releases
import com.lekaspos.core.update.Update
import com.lekaspos.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.Reader

import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.net.ssl.HttpsURLConnection

/**
 * New versions of the app from its GitHub releases (D-059, references/architecture.md §9a).
 *
 * Once a day (a WorkManager job, when online) the latest release is read from GitHub's API. When it
 * is a newer version, its APK is downloaded into `files/updates` and checked: its size and GitHub's
 * SHA-256 of it, then the package, a newer build and the same signing key as this app. The selling
 * screen shows "Update"; a tap (with the Settings permission) hands the file to Android's installer,
 * which asks to confirm and keeps every bit of data. Selling never waits for any of it.
 *
 * Nothing about the shop is sent: GitHub sees a request for the release list (the phone's address)
 * and the app version in the User-Agent. Release-signed builds look by themselves unless the shop
 * turned it off (per phone); any build can look by hand. A copy installed from Google Play never
 * updates itself (Play's rules).
 */
class AppUpdates(
    context: Context,
    private val prefsName: String = PREFS,
    private val dirName: String = DIR,
) {
    private val app = context.applicationContext

    enum class Problem {
        /** No internet (or GitHub could not be reached). */
        OFFLINE,

        /** GitHub refused or failed (busy, too many checks from this address). */
        SERVER,

        /** Not enough free storage for the download. */
        NO_SPACE,

        /** The download stopped. */
        DOWNLOAD,

        /** The file was not the one GitHub listed (size or SHA-256): deleted. */
        DAMAGED,

        /** Another app or another signing key than this copy (a test build, say). */
        WRONG_APP,

        /** Android cannot read it here (needs a newer Android), or it is not a newer build. */
        NOT_INSTALLABLE,
    }

    data class Status(
        /** The settings below are read (from a small file). */
        val loaded: Boolean = false,
        /** A newer version, or null when none is known. */
        val update: Update? = null,
        /** Its APK is downloaded and checked. */
        val ready: Boolean = false,
        val checking: Boolean = false,
        val downloading: Boolean = false,
        /** Percent, while downloading. */
        val progress: Int = 0,
        /** When GitHub last answered a check (0: never). */
        val checkedAt: Long = 0L,
        /** Why the last check or download failed; null after a good one. */
        val problem: Problem? = null,
        /**
         * The known update's file failed the checks ([Problem.WRONG_APP], [Problem.NOT_INSTALLABLE]):
         * it is not offered nor downloaded again until GitHub lists another file. Downloaded again
         * every day, it used the shop's data and kept "Update" on the screen for ever (2026-10 review).
         */
        val refused: Problem? = null,
        /** The daily check on this phone (the shop can turn it off). */
        val automatic: Boolean = true,
        /** Pre-releases count too (testers). */
        val testVersions: Boolean = false,
        /** False when Google Play installed this copy: Play updates it. */
        val selfUpdate: Boolean = true,
    )

    private val state = MutableStateFlow(Status())
    val status: StateFlow<Status> = state

    /** One check or download at a time. */
    private val busy = Mutex()

    @Volatile
    private var startDone = false

    // ---- settings and start ---------------------------------------------------------------------

    /** Reads the settings and what is known (once). */
    suspend fun load(): Status = withContext(Dispatchers.IO) { loadBlocking() }

    suspend fun setAutomatic(on: Boolean) = withContext(Dispatchers.IO) {
        loadBlocking()
        prefs().edit().putBoolean(K_AUTO, on).apply()
        state.update { it.copy(automatic = on) }
        schedule()
    }

    /** Test versions on or off; a test version already offered goes with "off". The caller checks again (another list). */
    suspend fun setTestVersions(on: Boolean) = withContext(Dispatchers.IO) {
        loadBlocking()
        val e = prefs().edit().putBoolean(K_TESTS, on).remove(K_ETAG).remove(K_ETAG_SRC)
        val dropTest = !on && state.value.update?.test == true
        if (dropTest) forget(e)
        e.apply()
        if (dropTest) clearFiles(keep = null)
        state.update { if (dropTest) it.copy(testVersions = on, update = null, ready = false) else it.copy(testVersions = on) }
    }

    /**
     * After the selling screen is usable, off the main thread: what is known is read, files of
     * versions now installed are deleted, and the daily check is scheduled. Returns this version's
     * name the first time it runs after an update (to say so), else null.
     */
    fun atStart(): String? {
        if (startDone) return null
        startDone = true
        loadBlocking()
        val p = prefs()
        val last = p.getInt(K_LAST_BUILD, 0)
        if (last != BuildConfig.VERSION_CODE) p.edit().putInt(K_LAST_BUILD, BuildConfig.VERSION_CODE).apply()
        schedule()
        return if (last in 1 until BuildConfig.VERSION_CODE) BuildConfig.VERSION_NAME else null
    }

    /** Release-signed, not a debug build, not from Google Play: may look for updates by itself. */
    private fun autoAllowed(): Boolean = BuildConfig.SIGNING_KEY == "release" && !BuildConfig.DEBUG && state.value.selfUpdate

    private fun schedule() {
        val s = state.value
        val on = s.automatic && autoAllowed()
        val age = System.currentTimeMillis() - s.checkedAt
        Work.updates(app, on, soon = on && age !in 0..DAY_MS)
    }

    // ---- check ----------------------------------------------------------------------------------

    /** Asks GitHub for the newest release. Network: suspends on IO. */
    suspend fun check(): Status = busy.withLock {
        withContext(Dispatchers.IO) {
            loadBlocking()
            state.update { it.copy(checking = true, problem = null) }
            try {
                fetchReleases()?.let { p -> state.update { it.copy(problem = p) } }
            } finally {
                state.update { it.copy(checking = false) }
            }
            state.value
        }
    }

    /** The answer saved and shown; a problem when there was none. */
    private fun fetchReleases(): Problem? {
        val p = prefs()
        val tests = p.getBoolean(K_TESTS, false)
        val src = if (tests) LIST_URL else LATEST_URL
        val etag = p.getString(K_ETAG, null)?.takeIf { p.getString(K_ETAG_SRC, null) == src }
        var c: HttpURLConnection? = null
        try {
            c = connect(src, "application/vnd.github+json", etag)
            val now = System.currentTimeMillis()
            when (val code = c.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val json = c.inputStream.use { readCapped(it, MAX_JSON) } ?: return Problem.SERVER
                    val releases = try {
                        ReleaseJson.read(json.inputStream().reader(Charsets.UTF_8))
                    } catch (e: IllegalStateException) { // JsonReader: not the shape GitHub documents
                        Log.w("Update check: GitHub's answer could not be read", e)
                        return Problem.SERVER
                    } catch (e: NumberFormatException) {
                        Log.w("Update check: GitHub's answer could not be read", e)
                        return Problem.SERVER
                    }
                    val update = Releases.choose(releases, BuildConfig.VERSION_NAME, tests)
                    known(update, now, c.getHeaderField("ETag"), src)
                }
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    p.edit().putLong(K_CHECKED, now).apply()
                    state.update { it.copy(checkedAt = now) }
                }
                HttpURLConnection.HTTP_NOT_FOUND -> known(null, now, null, src) // no release yet
                else -> {
                    // 403/429: GitHub allows 60 checks an hour per address (a shop's tills share one).
                    Log.w("Update check: GitHub answered $code")
                    return Problem.SERVER
                }
            }
            return null
        } catch (e: IOException) {
            Log.w("Update check failed", e)
            return networkProblem(e)
        } finally {
            c?.disconnect()
        }
    }

    /** GitHub's answer: [update] (null: none newer) saved, the old download dropped when it changed. */
    private fun known(update: Update?, now: Long, etag: String?, src: String) {
        val old = state.value.update
        // The same file (its notes may have been edited): what was downloaded stays.
        val same = update != null && old != null && update.version == old.version && update.sha256 == old.sha256
        val e = prefs().edit().putLong(K_CHECKED, now)
        if (etag != null) e.putString(K_ETAG, etag).putString(K_ETAG_SRC, src) else e.remove(K_ETAG).remove(K_ETAG_SRC)
        if (update == null) {
            forget(e)
        } else {
            e.putString(K_VERSION, update.version).putString(K_URL, update.url).putLong(K_SIZE, update.size)
                .putString(K_SHA, update.sha256).putBoolean(K_TEST, update.test).putString(K_NOTES, update.notes)
        }
        if (!same) e.remove(K_READY)
        val stillRefused = update != null && prefs().getString(K_REFUSED, null) == update.sha256
        if (!stillRefused) e.remove(K_REFUSED).remove(K_REFUSED_WHY)
        e.apply()
        if (!same) clearFiles(keep = null)
        state.update {
            it.copy(update = update, ready = same && it.ready, checkedAt = now, refused = if (stillRefused) it.refused else null)
        }
    }

    // ---- download -------------------------------------------------------------------------------

    /** Downloads and checks the known update (nothing to do when it is ready). Network: suspends on IO. */
    suspend fun download(): Status = busy.withLock {
        withContext(Dispatchers.IO) {
            loadBlocking()
            val u = state.value.update ?: return@withContext state.value
            if (state.value.ready && apkFile(u).length() == u.size) return@withContext state.value
            val known = state.value.refused
            if (known != null) {
                state.update { it.copy(problem = known) }
                return@withContext state.value
            }
            state.update { it.copy(downloading = true, progress = 0, problem = null) }
            val problem = try {
                val apk = apkFile(u)
                fetch(u, apk) ?: verify(apk, u.version).also { if (it != null) apk.delete() }
            } finally {
                state.update { it.copy(downloading = false) }
            }
            val refused = problem?.takeIf { it == Problem.WRONG_APP || it == Problem.NOT_INSTALLABLE }
            when {
                problem == null -> prefs().edit().putString(K_READY, u.sha256).apply()
                refused != null -> prefs().edit().putString(K_REFUSED, u.sha256).putString(K_REFUSED_WHY, refused.name).apply()
            }
            state.update { it.copy(ready = problem == null, problem = problem, refused = refused) }
            state.value
        }
    }

    /**
     * Downloads [u] into [into] (via a `.part` file) when its size and SHA-256 match GitHub's, else
     * a problem and no file. Progress goes to [status].
     */
    internal suspend fun fetch(u: Update, into: File): Problem? {
        clearFiles(keep = null)
        val dir = into.parentFile ?: return Problem.DOWNLOAD
        dir.mkdirs()
        val free = try {
            StatFs(dir.path).let { it.availableBlocksLong * it.blockSizeLong }
        } catch (e: IllegalArgumentException) {
            0L
        }
        if (free < spaceNeeded(u.size)) return Problem.NO_SPACE
        val part = File(dir, into.name + ".part")
        val sha = MessageDigest.getInstance("SHA-256")
        var c: HttpURLConnection? = null
        try {
            c = connect(u.url, "application/octet-stream", null)
            val code = c.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w("Update download: the server answered $code")
                return Problem.SERVER
            }
            var n = 0L
            var shown = -1
            c.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        coroutineContext.ensureActive()
                        val r = input.read(buf)
                        if (r < 0) break
                        n += r
                        if (n > u.size) break // longer than GitHub said: not the file it listed
                        sha.update(buf, 0, r)
                        out.write(buf, 0, r)
                        val pct = (n * 100 / u.size).toInt()
                        if (pct != shown) {
                            shown = pct
                            state.update { it.copy(progress = pct) }
                        }
                    }
                    out.fd.sync()
                }
            }
            if (n != u.size || hex(sha.digest()) != u.sha256) {
                Log.w("Update download: $n bytes, not the file GitHub listed (${u.size} bytes, SHA-256 ${u.sha256.take(8)}…)")
                return Problem.DAMAGED
            }
            into.delete()
            return if (part.renameTo(into)) null else Problem.DOWNLOAD
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w("Update download failed", e)
            return if (networkProblem(e) == Problem.OFFLINE) Problem.OFFLINE else Problem.DOWNLOAD
        } finally {
            c?.disconnect()
            part.delete()
        }
    }

    /**
     * The downloaded [apk] updates this app: Android can read it, the same package, a newer build,
     * and signed with this app's key (Android refuses another key anyway; checked here so the shop
     * gets a clear answer). Null when it does.
     */
    @SuppressLint("PackageManagerGetSignatures") // every signer is compared, not only the first
    @Suppress("DEPRECATION") // getPackageInfo(String, Int): the flags overload is API 33+
    internal fun verify(apk: File, version: String? = null): Problem? {
        val pm = app.packageManager
        val archive = archiveInfo(apk)
        if (archive == null) {
            Log.w("Update: Android cannot read the downloaded app (it may need a newer Android)")
            return Problem.NOT_INSTALLABLE
        }
        if (archive.packageName != app.packageName) {
            Log.w("Update: the download is ${archive.packageName}, this app is ${app.packageName}")
            return Problem.WRONG_APP
        }
        if (version != null && !sameVersion(archive.versionName.orEmpty(), version)) {
            // A release whose tag names another version than the app inside (versionName not raised):
            // once installed it still called itself the old version, so "Update" came back for ever
            // and installed the same build again (2026-10 review). The release went wrong.
            Log.e("Update: release $version holds version ${archive.versionName}")
            return Problem.NOT_INSTALLABLE
        }
        val build = versionCode(archive)
        if (build <= BuildConfig.VERSION_CODE) {
            Log.w("Update: the download is build $build, this app is build ${BuildConfig.VERSION_CODE}")
            return Problem.NOT_INSTALLABLE
        }
        val mine = signers(pm.getPackageInfo(app.packageName, SIGNATURE_FLAGS))
        val theirs = signers(archive)
        if (theirs.isEmpty() || mine.isEmpty()) {
            Log.w("Update: the signing key could not be read; Android checks it while installing")
        } else if (!theirs.containsAll(mine)) {
            // A release made with another key: the release went wrong (every shop would see this).
            Log.e("Update ${archive.versionName} is signed with another key: ${theirs.joinToString { it.take(16) }}")
            return Problem.WRONG_APP
        }
        return null
    }

    /** "1.8" and "1.8.0" (or "v1.8") are one version: compared number by number, missing ones as 0. */
    private fun sameVersion(a: String, b: String): Boolean {
        val x = Releases.version(a) ?: return false
        val y = Releases.version(b) ?: return false
        return Releases.compare(x, y) == 0
    }

    /** The downloaded app's package details with its signers, or null when Android cannot read it. */
    @Suppress("DEPRECATION") // the flags overload is API 33+
    internal fun archiveInfo(apk: File): PackageInfo? = try {
        app.packageManager.getPackageArchiveInfo(apk.path, SIGNATURE_FLAGS)
    } catch (e: Exception) {
        null
    }

    // ---- install --------------------------------------------------------------------------------

    /**
     * The intent that hands the downloaded update to Android's installer, after checking the file
     * once more; null when it is not ready (then [status] says why). Blocking: suspends on IO.
     */
    suspend fun installIntent(): Intent? = withContext(Dispatchers.IO) {
        loadBlocking()
        val u = state.value.update ?: return@withContext null
        val apk = apkFile(u)
        val intact = state.value.ready && apk.length() == u.size && hashOf(apk) == u.sha256
        if (!intact) {
            Log.w("Update: the downloaded file is gone or changed; it must be downloaded again")
            apk.delete()
            prefs().edit().remove(K_READY).apply()
            state.update { it.copy(ready = false, problem = Problem.DAMAGED) }
            return@withContext null
        }
        val uri = if (Build.VERSION.SDK_INT >= 24) {
            FileProvider.getUriForFile(app, "${app.packageName}.files", apk)
        } else {
            readableByInstaller(apk) // Android 5-6: the installer reads the file itself (file:// only)
            Uri.fromFile(apk)
        }
        @Suppress("DEPRECATION") // ACTION_INSTALL_PACKAGE: only package installers take it (no app chooser)
        Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, APK_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** The plain ACTION_VIEW form, for phones whose installer does not take ACTION_INSTALL_PACKAGE. */
    fun viewIntent(install: Intent): Intent = Intent(Intent.ACTION_VIEW).setDataAndType(install.data, APK_TYPE)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    @SuppressLint("SetWorldReadable") // Android 5-6 only: a public app, nothing secret in it
    private fun readableByInstaller(apk: File) {
        apk.setReadable(true, false)
        apk.parentFile?.setExecutable(true, false)
    }

    // ---- the daily job --------------------------------------------------------------------------

    /**
     * The background job: a check, then the download of a newer version, so "Update" installs at
     * once. False: try again later (offline). Never throws.
     */
    suspend fun background(): Boolean = try {
        load()
        if (!state.value.automatic || !autoAllowed()) {
            true
        } else {
            val s = check()
            when {
                s.problem == Problem.OFFLINE -> false
                s.problem != null -> true // GitHub busy or limiting: tomorrow
                s.update != null && !s.ready && s.refused == null -> download().problem.let { it != Problem.OFFLINE && it != Problem.DOWNLOAD }
                else -> true
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("The update check failed", e)
        true
    }

    // ---- storage --------------------------------------------------------------------------------

    private fun loadBlocking(): Status {
        if (state.value.loaded) return state.value
        synchronized(this) {
            if (state.value.loaded) return state.value
            val p = prefs()
            var u = savedUpdate(p)
            if (u != null && !Releases.isNewer(u.version, BuildConfig.VERSION_NAME)) {
                // Installed meanwhile (here, or from the website).
                p.edit().also { forget(it) }.remove(K_ETAG).remove(K_ETAG_SRC).apply()
                u = null
            }
            if (u == null) clearFiles(keep = null)
            val ready = u != null && p.getString(K_READY, null) == u.sha256 && apkFile(u).length() == u.size
            val refused = if (u != null && p.getString(K_REFUSED, null) == u.sha256) {
                p.getString(K_REFUSED_WHY, null)?.let { why -> Problem.values().firstOrNull { it.name == why } }
            } else {
                null
            }
            state.update {
                it.copy(
                    loaded = true,
                    update = u,
                    ready = ready,
                    refused = refused,
                    checkedAt = p.getLong(K_CHECKED, 0L),
                    automatic = p.getBoolean(K_AUTO, true),
                    testVersions = p.getBoolean(K_TESTS, false),
                    selfUpdate = !fromPlay(),
                )
            }
            return state.value
        }
    }

    /** No update known (and nothing downloaded for it). */
    private fun forget(e: SharedPreferences.Editor) {
        e.remove(K_VERSION).remove(K_URL).remove(K_SIZE).remove(K_SHA).remove(K_TEST).remove(K_NOTES).remove(K_READY)
            .remove(K_REFUSED).remove(K_REFUSED_WHY)
    }

    private fun savedUpdate(p: SharedPreferences): Update? {
        val version = p.getString(K_VERSION, null) ?: return null
        val url = p.getString(K_URL, null) ?: return null
        val sha = p.getString(K_SHA, null) ?: return null
        return Update(version, url, p.getLong(K_SIZE, 0L), sha, p.getBoolean(K_TEST, false), p.getString(K_NOTES, null).orEmpty())
    }

    /** Google Play installed this copy (its updates come from Play). */
    private fun fromPlay(): Boolean = try {
        val installer = if (Build.VERSION.SDK_INT >= 30) {
            app.packageManager.getInstallSourceInfo(app.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            app.packageManager.getInstallerPackageName(app.packageName)
        }
        installer == PLAY_STORE
    } catch (e: Exception) {
        false
    }

    private fun prefs(): SharedPreferences = app.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private fun dir() = File(app.filesDir, dirName)

    internal fun apkFile(u: Update) = File(dir(), "LekasPOS-${u.version}.apk")

    /** Deletes every download but [keep]'s (unfinished ones too). */
    private fun clearFiles(keep: Update?) {
        val name = keep?.let { apkFile(it).name }
        dir().listFiles()?.forEach { if (it.name != name) it.delete() }
    }

    /** The phone could not reach GitHub at all (no internet, no name, timed out) or something else failed. */
    private fun networkProblem(e: IOException): Problem =
        // SocketException (ConnectException among them): the connection broke or the network went away.
        if (e is UnknownHostException || e is SocketException || e is SocketTimeoutException) Problem.OFFLINE else Problem.SERVER

    /** HTTPS only, redirects followed by hand (GitHub sends downloads to another host), at most [MAX_REDIRECTS]. */
    private fun connect(url: String, accept: String, etag: String?): HttpURLConnection {
        var target = URL(url)
        for (hop in 0..MAX_REDIRECTS) {
            if (target.protocol != "https") throw IOException("Not HTTPS: ${target.protocol}://${target.host}")
            val c = target.openConnection() as HttpURLConnection
            // Current TLS (Play services) and, on Android 5-7, the bundled roots (PublicTrust).
            PublicTrust.sockets(app)?.let { (c as? HttpsURLConnection)?.sslSocketFactory = it }
            c.instanceFollowRedirects = false
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.setRequestProperty("User-Agent", "LekasPOS/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.SDK_INT})")
            c.setRequestProperty("Accept", accept)
            if (target.host == API_HOST) c.setRequestProperty("X-GitHub-Api-Version", API_VERSION)
            if (etag != null && hop == 0) c.setRequestProperty("If-None-Match", etag)
            val code = c.responseCode
            if (code !in REDIRECTS) return c
            val next = c.getHeaderField("Location")
            c.disconnect()
            target = URL(target, next ?: throw IOException("A redirect without a location"))
        }
        throw IOException("Too many redirects")
    }

    companion object {
        private const val PREFS = "lekas_updates"
        private const val DIR = "updates"
        private const val API_HOST = "api.github.com"
        private const val API_VERSION = "2022-11-28"
        private const val LATEST_URL = "https://$API_HOST/repos/FaizoKen/LekasPOS/releases/latest"
        private const val LIST_URL = "https://$API_HOST/repos/FaizoKen/LekasPOS/releases?per_page=10"
        private const val PLAY_STORE = "com.android.vending"
        const val APK_TYPE = "application/vnd.android.package-archive"

        private const val K_AUTO = "auto"
        private const val K_TESTS = "tests"
        private const val K_CHECKED = "checked_at"
        private const val K_ETAG = "etag"
        private const val K_ETAG_SRC = "etag_src"
        private const val K_VERSION = "version"
        private const val K_URL = "url"
        private const val K_SIZE = "size"
        private const val K_SHA = "sha256"
        private const val K_TEST = "test"
        private const val K_NOTES = "notes"
        private const val K_READY = "ready_sha256"
        private const val K_LAST_BUILD = "last_build"
        private const val K_REFUSED = "refused_sha256"
        private const val K_REFUSED_WHY = "refused_why"

        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private const val MAX_REDIRECTS = 5
        private const val TIMEOUT_MS = 30_000
        private const val MAX_JSON = 2 * 1024 * 1024
        private const val BUFFER = 16 * 1024
        private const val SPARE_BYTES = 16L * 1024 * 1024
        private const val DAY_MS = 24L * 3600 * 1000

        /** Free storage a download of [size] bytes needs: the file, Android's copy while installing, a margin. */
        fun spaceNeeded(size: Long): Long = size * 2 + SPARE_BYTES

        @Suppress("DEPRECATION")
        private val SIGNATURE_FLAGS = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNATURES or PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

        /** SHA-256 (hex) of each certificate a package is signed with, including a rotated key's history. */
        @Suppress("DEPRECATION")
        internal fun signers(pi: PackageInfo): Set<String> {
            val certs: Array<Signature>? = (if (Build.VERSION.SDK_INT >= 28) signingInfoCerts(pi) else null) ?: pi.signatures
            return certs.orEmpty().map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
        }

        /** Android 9+: the signers, with the history of a rotated key; null when not given. */
        @RequiresApi(28)
        private fun signingInfoCerts(pi: PackageInfo): Array<Signature>? {
            val info = pi.signingInfo ?: return null
            return if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        }

        @Suppress("DEPRECATION")
        private fun versionCode(pi: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()

        private fun hashOf(f: File): String {
            val sha = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sha.update(buf, 0, n)
                }
            }
            return hex(sha.digest())
        }

        private fun hex(b: ByteArray): String {
            val sb = StringBuilder(b.size * 2)
            for (x in b) {
                val v = x.toInt() and 0xff
                sb.append(HEX[v shr 4]).append(HEX[v and 0xf])
            }
            return sb.toString()
        }

        private const val HEX = "0123456789abcdef"

        /** At most [max] bytes, or null when there are more. */
        private fun readCapped(input: InputStream, max: Int): ByteArray? {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) return out.toByteArray()
                if (out.size() + n > max) return null
                out.write(buf, 0, n)
            }
        }
    }
}

/** GitHub's release JSON: one release (`/releases/latest`) or a list (`/releases`); unknown fields skipped. */
internal object ReleaseJson {

    fun read(input: Reader): List<Releases.Release> = JsonReader(input).use { r ->
        when (r.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                val list = ArrayList<Releases.Release>()
                r.beginArray()
                while (r.hasNext()) list.add(release(r))
                r.endArray()
                list
            }
            JsonToken.BEGIN_OBJECT -> listOf(release(r))
            else -> emptyList()
        }
    }

    private fun release(r: JsonReader): Releases.Release {
        var tag = ""
        var draft = false
        var pre = false
        var body = ""
        var assets = emptyList<Releases.Asset>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "tag_name" -> tag = string(r)
                "draft" -> draft = bool(r)
                "prerelease" -> pre = bool(r)
                "body" -> body = string(r)
                "assets" -> assets = assets(r)
                else -> r.skipValue()
            }
        }
        r.endObject()
        return Releases.Release(tag, draft, pre, body, assets)
    }

    private fun assets(r: JsonReader): List<Releases.Asset> {
        if (r.peek() != JsonToken.BEGIN_ARRAY) {
            r.skipValue()
            return emptyList()
        }
        val list = ArrayList<Releases.Asset>()
        r.beginArray()
        while (r.hasNext()) {
            var name = ""
            var url = ""
            var size = 0L
            var digest = ""
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "name" -> name = string(r)
                    "browser_download_url" -> url = string(r)
                    "size" -> size = if (r.peek() == JsonToken.NUMBER) r.nextLong() else 0L.also { r.skipValue() }
                    "digest" -> digest = string(r)
                    else -> r.skipValue()
                }
            }
            r.endObject()
            list.add(Releases.Asset(name, url, size, digest))
        }
        r.endArray()
        return list
    }

    private fun string(r: JsonReader): String = if (r.peek() == JsonToken.STRING) r.nextString() else "".also { r.skipValue() }

    private fun bool(r: JsonReader): Boolean = if (r.peek() == JsonToken.BOOLEAN) r.nextBoolean() else false.also { r.skipValue() }
}
