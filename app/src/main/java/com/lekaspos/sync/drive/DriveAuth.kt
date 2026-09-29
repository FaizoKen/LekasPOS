package com.lekaspos.sync.drive

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.Scope
import com.google.android.gms.security.ProviderInstaller
import com.google.android.gms.tasks.Tasks
import com.lekaspos.sync.AuthNeeded
import com.lekaspos.util.Log
import java.io.IOException
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

    /** [account]: the store's Google account once known, so a phone with several accounts asks for the right one. */
    private fun request(account: String?): AuthorizationRequest {
        val b = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DriveProvider.SCOPE)))
        if (account != null) b.setAccount(Account(account, "com.google"))
        return b.build()
    }

    fun playServicesAvailable(ctx: Context): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS

    /** Blocks on Play Services: never call on the main thread. */
    suspend fun authorize(ctx: Context, account: String? = null): Result = withContext(Dispatchers.IO) {
        if (!playServicesAvailable(ctx)) return@withContext Result.Unavailable("Google Play services are not available")
        try {
            ProviderInstaller.installIfNeeded(ctx) // up-to-date TLS on old Android
        } catch (e: Exception) {
            Log.w("Security provider update failed", e)
        }
        try {
            val r = Tasks.await(Identity.getAuthorizationClient(ctx).authorize(request(account)))
            val pending = r.pendingIntent
            val token = r.accessToken
            when {
                r.hasResolution() && pending != null -> Result.NeedsUser(pending)
                token != null -> Result.Token(token)
                else -> Result.Unavailable("no access token")
            }
        } catch (e: Exception) {
            Log.w("Google authorization failed", e)
            Result.Unavailable(e.message ?: e.javaClass.simpleName)
        }
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
}
