package com.lekaspos.sync

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import com.lekaspos.app.AppGraph
import com.lekaspos.app.Work
import com.lekaspos.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * When this till syncs while the app is running (D-053): [CHANGE_DELAY_MS] after it changes
 * something (a sale, a price, a stock count … — anything queued for upload), a few seconds after
 * the app opens (the other tills' changes), when the internet comes back, and at once when asked.
 * Automatic rounds are at least [MIN_GAP_MS] apart, so a busy till is not syncing all day;
 * WorkManager's jobs ([Work]) remain the fallback while the app is closed.
 */
class AutoSync(private val graph: AppGraph, private val app: Application) {

    private val lock = Any()
    private var planned: Job? = null
    private var plannedAt = 0L

    @Volatile
    private var lastRoundAt = -MIN_GAP_MS
    private var watching = false

    /** Failed automatic rounds in a row (each is tried again a little later, a few times). */
    @Volatile
    private var failures = 0
    private val refreshQueued = AtomicBoolean(false)

    /** This till queued a change for upload (called on the database writer thread). */
    fun changed() {
        // "1 change waiting" shows at once — one refresh at a time, and none during a round (it
        // reports for itself): the first sync's backfill commits hundreds of chunks.
        if (!graph.sync.status.value.running && refreshQueued.compareAndSet(false, true)) {
            graph.appScope.launch(Dispatchers.IO) {
                refreshQueued.set(false)
                graph.sync.refreshStatus()
            }
        }
        plan(CHANGE_DELAY_MS, asked = false)
    }

    /** As soon as possible: the sync screen's button, or the screen opening with changes waiting. */
    fun now() = plan(0L, asked = true)

    /** The app is open: sync shortly, and whenever the internet comes back. */
    fun start() {
        plan(START_DELAY_MS, asked = false)
        watchNetwork()
    }

    private fun plan(delayMs: Long, asked: Boolean, gap: Boolean = !asked) {
        val now = SystemClock.elapsedRealtime()
        val wait = if (!gap) delayMs else maxOf(delayMs, lastRoundAt + MIN_GAP_MS - now)
        val at = now + wait
        if (asked) graph.sync.starting() // the screen shows "connecting" at once
        synchronized(lock) {
            val p = planned
            if (p != null && p.isActive && plannedAt <= at) return // an earlier round is already planned
            p?.cancel()
            plannedAt = at
            planned = graph.appScope.launch(Dispatchers.IO) {
                if (wait > 0L) delay(wait)
                // From here on this round is not cancelled by later plans: they wait for it instead —
                // also while it waits for a round already running. Only once it has started (it may
                // have sealed the changes already) does a new change plan another round.
                val me = coroutineContext[Job]
                val release = { synchronized(lock) { if (planned === me) planned = null } }
                try {
                    round(release)
                } finally {
                    release()
                }
            }
        }
    }

    private suspend fun round(onStart: () -> Unit) {
        try {
            val provider = graph.sync.provider()
            if (provider == null) {
                graph.sync.notStarted()
                return
            }
            if (!online()) {
                graph.sync.notStarted(SyncEngine.ERROR_OFFLINE) // the network callback starts it again
                return
            }
            val report = graph.sync.sync(provider, onStart)
            failures = 0
            // The fallback job goes only when nothing is left to send (a sale during the round is).
            if (graph.sync.status.value.pending == 0L) {
                Work.cancelSyncSoon(app)
                graph.syncSoonDone()
            }
            // Work left (a long history read in parts, or this till publishing again): go on at once,
            // not after the usual gap between automatic rounds (2026-10 review).
            if (report.more) plan(CONTINUE_DELAY_MS, asked = false, gap = false)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Auto sync did not finish", e) // shown in the sync status
            // Tried again a little later, a few times; no internet is handled by the network callback,
            // and a sign-in needs the owner.
            val code = SyncEngine.errorCode(e)
            if (code != SyncEngine.ERROR_OFFLINE && code != SyncEngine.ERROR_SIGN_IN && ++failures <= MAX_RETRIES) {
                lastRoundAt = SystemClock.elapsedRealtime()
                plan(RETRY_DELAY_MS * failures, asked = false)
            }
        } finally {
            lastRoundAt = SystemClock.elapsedRealtime()
        }
    }

    private fun online(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        @Suppress("DEPRECATION") // NetworkCapabilities needs API 23; this works on every version
        return cm.activeNetworkInfo?.isConnected == true
    }

    /** Syncs when a network with internet appears (after being offline, or right now at start). */
    private fun watchNetwork() {
        synchronized(lock) {
            if (watching) return
            watching = true
        }
        try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            cm.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        val s = graph.sync.status.value
                        if (s.enabled && (s.pending > 0L || s.lastError == SyncEngine.ERROR_OFFLINE)) plan(NETWORK_DELAY_MS, asked = false)
                    }
                },
            )
        } catch (e: Exception) {
            Log.w("Cannot watch the network", e) // some Android 6.0 builds refuse; the other triggers still work
        }
    }

    companion object {
        const val CHANGE_DELAY_MS = 10_000L
        const val START_DELAY_MS = 5_000L
        const val NETWORK_DELAY_MS = 3_000L
        const val MIN_GAP_MS = 45_000L
        const val RETRY_DELAY_MS = 60_000L
        const val MAX_RETRIES = 3
        const val CONTINUE_DELAY_MS = 2_000L
    }
}
