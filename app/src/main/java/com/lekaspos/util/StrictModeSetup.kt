package com.lekaspos.util

import android.os.StrictMode

/**
 * Debug builds only. Disk and network access on the main thread are logged (some framework
 * code does small reads we cannot control); network on the main thread already throws
 * NetworkOnMainThreadException on API 21+, and our DB code refuses the main thread itself
 * (Db.assertNotMainThread). Leaked cursors, closeables and activities are logged.
 */
object StrictModeSetup {
    fun enable() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build(),
        )
    }
}
