package com.lekaspos.util

import com.lekaspos.BuildConfig

/**
 * The app's only logger. Debug messages are lambdas behind a compile-time constant, so release
 * builds drop both the call and the string building. Never log PINs, tokens or customer data.
 */
object Log {
    const val TAG = "Lekas"

    inline fun d(message: () -> String) {
        if (BuildConfig.DEBUG) android.util.Log.d(TAG, message())
    }

    fun i(message: String) {
        android.util.Log.i(TAG, message)
    }

    fun w(message: String, t: Throwable? = null) {
        android.util.Log.w(TAG, message, t)
        ErrorLog.append("W", message, t)
    }

    /**
     * Whether a failure comes from the world, not from the app: a full phone, a rule refusing the
     * action, a job stopped on purpose, no internet, a file the user picked that is no backup. Such a
     * failure passed to [e] is told as a warning, never as an error report to the developer (D-057):
     * they hid the real bugs among the reports (2026-10 review). Set at start (app.ErrorReports).
     */
    @Volatile
    var expected: (Throwable) -> Boolean = { false }

    fun e(message: String, t: Throwable? = null) {
        if (t != null && expected(t)) {
            w(message, t)
            return
        }
        android.util.Log.e(TAG, message, t)
        ErrorLog.append("E", message, t)
    }
}
