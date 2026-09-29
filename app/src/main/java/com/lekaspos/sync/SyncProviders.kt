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
    suspend fun connect(ctx: Context): Connect = when (val r = DriveAuth.authorize(ctx.applicationContext)) {
        is DriveAuth.Result.Token -> ready(ctx, r.token)
        is DriveAuth.Result.NeedsUser -> Connect.NeedsUser(r.intent)
        is DriveAuth.Result.Unavailable -> Connect.Unavailable(r.message)
    }

    /** After the consent screen: [data] is its result intent. */
    suspend fun finish(ctx: Context, data: Intent?): Connect {
        val token = DriveAuth.fromIntent(ctx.applicationContext, data) ?: return Connect.Unavailable("access was not granted")
        return ready(ctx, token)
    }

    private suspend fun ready(ctx: Context, token: String): Connect {
        val provider = drive(ctx.applicationContext, token)
        val account = try {
            provider.accountEmail()
        } catch (e: Exception) {
            Log.w("Cannot read the Google account name", e)
            null
        }
        return Connect.Ready(provider, account)
    }

    /** Google Drive with tokens from Google Identity Services; [first] is a token the UI just got. */
    private fun drive(ctx: Context, first: String? = null): DriveProvider {
        var cached = first
        return DriveProvider { refresh ->
            val t = cached
            if (t != null && !refresh) {
                t
            } else {
                DriveAuth.silentToken(ctx).also { cached = it }
            }
        }
    }

    fun forId(ctx: Context, id: String?): SyncProvider? = when (id) {
        GDRIVE -> drive(ctx.applicationContext)
        else -> null
    }
}
