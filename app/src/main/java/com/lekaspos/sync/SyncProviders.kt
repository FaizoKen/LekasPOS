package com.lekaspos.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.lekaspos.sync.drive.DriveAuth
import com.lekaspos.sync.drive.DriveProvider
import com.lekaspos.util.Log

/**
 * Which provider this till syncs with (stored in `meta` as [SyncEngine.PROVIDER]), and the
 * connect flow the sync screen runs. Nothing outside this package knows it is Google Drive.
 */
object SyncProviders {

    const val GDRIVE = "gdrive"

    sealed class Connect {
        class Ready(val provider: SyncProvider, val account: String?) : Connect()

        /** The provider needs the user: start [intent] for a result, then call [finish]. */
        class NeedsUser(val intent: PendingIntent) : Connect()

        class Unavailable(val message: String) : Connect()
    }

    /** False when the phone has no Google Play services (sync is then off; backups still work). */
    fun available(ctx: Context): Boolean = DriveAuth.playServicesAvailable(ctx)

    /** Asks for access to the store's sync folder. Never on the main thread's time: it is a suspend call on IO. */
    suspend fun connect(ctx: Context, account: String? = null): Connect = when (val r = DriveAuth.authorize(ctx.applicationContext, account)) {
        is DriveAuth.Result.Token -> ready(ctx, r.token, account)
        is DriveAuth.Result.NeedsUser -> Connect.NeedsUser(r.intent)
        is DriveAuth.Result.Unavailable -> Connect.Unavailable(r.message)
    }

    /** After the consent screen: [data] is its result intent. */
    suspend fun finish(ctx: Context, data: Intent?): Connect {
        val token = DriveAuth.fromIntent(ctx.applicationContext, data) ?: return Connect.Unavailable("access was not granted")
        return ready(ctx, token, null)
    }

    private suspend fun ready(ctx: Context, token: String, known: String?): Connect {
        var provider = drive(ctx.applicationContext, token, known)
        val account = try {
            provider.accountEmail()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not "ready" without knowing which account: the check against signing in with another
            // account than the till's needs it, and a till that synced once into the wrong account's
            // empty folder left a gap the other tills never read past (2026-10 review). The screen
            // says why (offline …) and the user tries again.
            Log.w("Cannot read the Google account name", e)
            throw e
        }
        if (account != null && known == null) provider = drive(ctx.applicationContext, token, account)
        return Connect.Ready(provider, account ?: known)
    }

    /**
     * Google Drive with tokens from Google Identity Services; [first] is a token the UI just got.
     * Tokens are kept for the whole app process (Google's last about an hour), so a sync round does
     * not wait for Play services each time (D-053); a 401 fetches a new one.
     */
    private fun drive(ctx: Context, first: String?, account: String?, scope: String = DriveProvider.SCOPE): DriveProvider {
        if (first != null) Tokens.put(scope, account, first)
        return DriveProvider { refresh ->
            // Google refused the token: Play services must not hand the same one out again (2026-10 review).
            if (refresh) Tokens.take(scope, account)?.let { DriveAuth.forget(ctx, it) }
            Tokens.get(scope, account) ?: DriveAuth.silentToken(ctx, account, scope).also { Tokens.put(scope, account, it) }
        }
    }

    /** Access tokens by scope and account, for [TOKEN_TTL_MS] (Google's are valid for about an hour). */
    private object Tokens {
        private class Entry(val token: String, val at: Long)

        private val byAccount = java.util.concurrent.ConcurrentHashMap<String, Entry>()

        private fun key(scope: String, account: String?) = "$scope ${account.orEmpty()}"

        fun get(scope: String, account: String?): String? {
            val e = byAccount[key(scope, account)] ?: return null
            return e.token.takeIf { android.os.SystemClock.elapsedRealtime() - e.at < TOKEN_TTL_MS }
        }

        fun put(scope: String, account: String?, token: String) {
            byAccount[key(scope, account)] = Entry(token, android.os.SystemClock.elapsedRealtime())
        }

        /** Removes and returns the cached token (expired or not). */
        fun take(scope: String, account: String?): String? = byAccount.remove(key(scope, account))?.token
    }

    private const val TOKEN_TTL_MS = 45L * 60L * 1000L

    fun forId(ctx: Context, id: String?, account: String?): SyncProvider? = when (id) {
        GDRIVE -> drive(ctx.applicationContext, null, account)
        else -> null
    }

    // ------------------------------------------------------------------ the daily sales report (D-065)

    /** The folder at the top of the owner's Drive that the daily report goes into. */
    const val REPORT_FOLDER = "LekasPOS"

    sealed class ReportAccess {
        /** Access granted for [account] (its e-mail). */
        class Ready(val account: String) : ReportAccess()

        /** Google needs the user: start [intent] for a result, then call [finishReports]. */
        class NeedsUser(val intent: PendingIntent) : ReportAccess()

        class Unavailable(val message: String) : ReportAccess()
    }

    /**
     * Asks for the daily report's own access: files this app makes in the owner's Drive (`drive.file`),
     * nothing else of theirs. Straight to Google's Drive service: any Drive app, or none, will do.
     */
    suspend fun connectReports(ctx: Context, account: String?): ReportAccess =
        when (val r = DriveAuth.authorize(ctx.applicationContext, account, DriveProvider.FILE_SCOPE)) {
            is DriveAuth.Result.Token -> reportsReady(ctx, r.token, account)
            is DriveAuth.Result.NeedsUser -> ReportAccess.NeedsUser(r.intent)
            is DriveAuth.Result.Unavailable -> ReportAccess.Unavailable(r.message)
        }

    /** After the consent screen of [connectReports]: [data] is its result intent. */
    suspend fun finishReports(ctx: Context, data: Intent?): ReportAccess {
        val token = DriveAuth.fromIntent(ctx.applicationContext, data) ?: return ReportAccess.Unavailable("access was not granted")
        return reportsReady(ctx, token, null)
    }

    /** Which account was granted (the uploads ask for it by name, the screen shows it). */
    private suspend fun reportsReady(ctx: Context, token: String, known: String?): ReportAccess {
        val account = drive(ctx.applicationContext, token, known, DriveProvider.FILE_SCOPE).accountEmail() ?: known
            ?: throw java.io.IOException("Google did not say which account")
        Tokens.put(DriveProvider.FILE_SCOPE, account, token)
        return ReportAccess.Ready(account)
    }

    /** [account]'s "LekasPOS" folder, made when it is not there (deleted, in the bin). */
    fun reportFolder(ctx: Context, account: String): ReportFolder {
        val drive = drive(ctx.applicationContext, null, account, DriveProvider.FILE_SCOPE)
        return object : ReportFolder {
            override suspend fun put(name: String, file: java.io.File, mime: String) =
                drive.putInFolder(drive.folder(REPORT_FOLDER), name, file, mime)
        }
    }
}
