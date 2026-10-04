package com.lekaspos.sync.drive

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.security.ProviderInstaller
import com.google.android.gms.tasks.Tasks
import com.lekaspos.sync.AuthNeeded
import com.lekaspos.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Access to the store's Google Drive app folder with Google Identity Services'
 * AuthorizationClient (D-014): scope `drive.appdata` only, no ID token, no Google Sign-In SDK.
 * Background syncs ask silently; when Google needs the user (first time, revoked access) the
 * sync status says so and the sync screen shows Google's consent screen.
 */
object DriveAuth {

    sealed class Result {
        data class Token(val token: String) : Result()

        /** Google needs the user: start [intent] for a result, then pass it to [fromIntent]. */
        data class NeedsUser(val intent: PendingIntent) : Result()

        data class Unavailable(val message: String) : Result()
    }

    class SignInNeeded : AuthNeeded("Google sign-in needed")

    @Volatile
    private var securityChecked = false

    /** [account]: the store's Google account once known, so a phone with several accounts asks for the right one. */
    private fun request(account: String?): AuthorizationRequest {
        val b = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DriveProvider.SCOPE)))
        if (account != null) b.setAccount(Account(account, "com.google"))
        return b.build()
    }

    fun playServicesAvailable(ctx: Context): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS

    /** Blocks on Play Services: never call on the main thread. Throws an IOException when Google cannot be reached. */
    suspend fun authorize(ctx: Context, account: String? = null): Result = withContext(Dispatchers.IO) {
        if (!playServicesAvailable(ctx)) return@withContext Result.Unavailable("Google Play services are not available")
        if (!securityChecked) {
            securityChecked = true // once per app process: it can take a second or more
            try {
                ProviderInstaller.installIfNeeded(ctx) // up-to-date TLS on old Android
            } catch (e: Exception) {
                Log.w("Security provider update failed", e)
            }
        }
        try {
            // Bounded (2026-10 review): a call Play services never answers would hold the sync lock
            // for good — every later round, and turning sync off, would wait behind it.
            val task = Identity.getAuthorizationClient(ctx).authorize(request(account))
            val r = Tasks.await(task, AUTH_TIMEOUT_S, TimeUnit.SECONDS)
            val pending = r.pendingIntent
            val token = r.accessToken
            when {
                r.hasResolution() && pending != null -> Result.NeedsUser(pending)
                token != null -> Result.Token(token)
                else -> Result.Unavailable("no access token")
            }
        } catch (e: Exception) {
            // No network, or Play services did not answer in time: thrown as the network failure it is,
            // so the screens say "offline" and a round is retried like any other without internet (it
            // showed Google's "7: " or "TimeoutException", 2026-10 review).
            if (unreachable(e)) throw java.net.ConnectException("Google could not be reached").apply { initCause(e) }
            Log.w("Google authorization failed", e)
            Result.Unavailable(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun unreachable(e: Exception): Boolean {
        val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
        return cause is java.util.concurrent.TimeoutException || cause is IOException ||
            (cause is ApiException && (cause.statusCode == CommonStatusCodes.NETWORK_ERROR || cause.statusCode == CommonStatusCodes.TIMEOUT))
    }

    /** The token from the consent screen's result. */
    fun fromIntent(ctx: Context, data: Intent?): String? = try {
        Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(data).accessToken
    } catch (e: Exception) {
        Log.w("Google consent failed", e)
        null
    }

    /** A token for a background sync, or [SignInNeeded] when the user must act first. */
    suspend fun silentToken(ctx: Context, account: String?): String = when (val r = authorize(ctx, account)) {
        is Result.Token -> r.token
        is Result.NeedsUser -> throw SignInNeeded()
        is Result.Unavailable -> throw IOException(r.message)
    }

    /**
     * Drops a token Google refused from Play services' cache (2026-10 review): asked again, it
     * would hand out the same one until it expires, and "Sign in again" could not help meanwhile.
     */
    suspend fun forget(ctx: Context, token: String) = withContext(Dispatchers.IO) {
        try {
            GoogleAuthUtil.clearToken(ctx, token)
        } catch (e: Exception) {
            Log.w("Clearing a refused token failed", e)
        }
    }

    private const val AUTH_TIMEOUT_S = 30L
}
