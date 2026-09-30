package com.lekaspos.perf

import android.content.Context
import android.os.Debug
import java.io.File

/**
 * Saves a heap dump when a leak test fails, to the app's internal `files/leaks/`. CI pulls it
 * (scripts/ci/instrumented.sh) and prints the reference chain with LeakCanary's shark-cli.
 */
object HeapDumps {
    fun save(ctx: Context, name: String) {
        val dir = File(ctx.filesDir, "leaks").apply { mkdirs() }
        Debug.dumpHprofData(File(dir, "$name.hprof").path)
    }
}
