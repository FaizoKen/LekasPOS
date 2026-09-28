package com.lekaspos.perf

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lekaspos.testing.TestDb
import java.io.File
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/**
 * Runs the perf suite (references/performance.md §2). Always enforced: every hot query uses an
 * index. Timing budgets are enforced only with `-e assertBudgets true`, because instrumented
 * tests run the debuggable build; the release-build verdict comes from scripts/run-perf.ps1.
 *
 * Arguments: `-e perfScale TINY|QUICK|FULL` (default QUICK), `-e assertBudgets true`.
 */
@RunWith(AndroidJUnit4::class)
class PerfSuiteTest {

    @After
    fun tearDown() {
        PerfDataGenerator.delete(TestDb.context)
    }

    @Test
    fun perfSuiteRunsAndHotQueriesUseIndexes() {
        val args = InstrumentationRegistry.getArguments()
        val scale = PerfScale.valueOf(args.getString("perfScale") ?: "QUICK")
        val assertBudgets = args.getString("assertBudgets") == "true"
        val ctx = TestDb.context

        val t0 = SystemClock.elapsedRealtime()
        val db = PerfDataGenerator(ctx, scale).generate()
        val generationMs = SystemClock.elapsedRealtime() - t0
        val report = db.use { PerfSuite(ctx, it, scale).run(generationMs) }

        for (line in report.toText().lines()) android.util.Log.i(PerfRunner.LOG_TAG, line)
        val dir = ctx.getExternalFilesDir("perf") ?: File(ctx.filesDir, "perf")
        dir.mkdirs()
        File(dir, "instrumented-${scale.name}.json").writeText(report.toJson())

        val planViolations = report.plans.flatMap { p -> p.violations.map { "${p.name}: $it" } }
        assertTrue(planViolations.isEmpty(), "Hot queries without an index:\n" + planViolations.joinToString("\n"))
        if (assertBudgets) {
            val over = report.results.filterNot { it.pass }
            assertTrue(over.isEmpty(), "Over budget:\n" + over.joinToString("\n") { "${it.id} p95=${it.p95Ms} ms > ${it.budgetMs} ms" })
        }
    }
}
