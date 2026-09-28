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
    }

    fun e(message: String, t: Throwable? = null) {
        android.util.Log.e(TAG, message, t)
    }
}
