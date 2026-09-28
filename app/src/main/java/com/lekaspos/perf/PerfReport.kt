package com.lekaspos.perf

import android.util.JsonWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val release: String,
    val totalRamMb: Long,
    val lowRamDevice: Boolean,
    val memoryClassMb: Int,
    val sqliteVersion: String,
    val appVersion: String,
    val abi: String,
)

data class PerfResult(
    val id: String,
    val budgetMs: Double,
    val n: Int,
    val minMs: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val maxMs: Double,
) {
    val pass: Boolean get() = p95Ms <= budgetMs

    companion object {
        fun from(id: String, budgetMs: Double, nanos: LongArray): PerfResult {
            val s = nanos.copyOf()
            s.sort()
            fun pct(q: Double): Double {
                val idx = (Math.ceil(q * s.size) - 1).toInt().coerceIn(0, s.size - 1)
                return s[idx] / 1_000_000.0
            }
            return PerfResult(id, budgetMs, s.size, s[0] / 1e6, pct(0.50), pct(0.95), pct(0.99), s[s.size - 1] / 1e6)
        }
    }
}

data class PlanCheck(val name: String, val plan: List<String>, val violations: List<String>)

data class PerfReport(
    val device: DeviceInfo,
    val scale: PerfScale,
    val startedAt: Long,
    val generationMs: Long,
    val dbSizeBytes: Long,
    val counts: Map<String, Long>,
    val results: List<PerfResult>,
    val plans: List<PlanCheck>,
) {
    val passed: Boolean get() = results.all { it.pass } && plans.all { it.violations.isEmpty() }

    fun toText(): String {
        val sb = StringBuilder(4096)
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(startedAt))
        sb.append("LekasPOS performance report — ").append(date).append('\n')
        sb.append("Result: ").append(if (passed) "PASS" else "FAIL").append('\n')
        sb.append("Device: ${device.manufacturer} ${device.model}, Android ${device.release} (API ${device.sdkInt}), ")
            .append("RAM ${device.totalRamMb} MB${if (device.lowRamDevice) " (low-RAM)" else ""}, ")
            .append("heap class ${device.memoryClassMb} MB, ${device.abi}\n")
        sb.append("SQLite ${device.sqliteVersion}, app ${device.appVersion}\n")
        sb.append("Scale: ${scale.name} — ")
        counts.entries.joinTo(sb, ", ") { "${it.key} ${it.value}" }
        sb.append('\n')
        sb.append("Test data generated in ${generationMs / 1000} s, database ${dbSizeBytes / (1024 * 1024)} MB\n\n")
        sb.append(String.format(Locale.US, "%-22s %8s %8s %8s %8s %8s  %s\n", "scenario", "p50 ms", "p95 ms", "max ms", "budget", "n", ""))
        for (r in results) {
            sb.append(
                String.format(
                    Locale.US, "%-22s %8.2f %8.2f %8.2f %8.0f %8d  %s\n",
                    r.id, r.p50Ms, r.p95Ms, r.maxMs, r.budgetMs, r.n, if (r.pass) "ok" else "OVER BUDGET",
                ),
            )
        }
        sb.append("\nQuery plans:\n")
        for (p in plans) {
            sb.append(if (p.violations.isEmpty()) "  ok   " else "  FAIL ").append(p.name).append('\n')
            for (v in p.violations) sb.append("         ").append(v).append('\n')
        }
        return sb.toString()
    }

    fun toJson(): String {
        val out = StringWriter(8192)
        JsonWriter(out).use { w ->
            w.setIndent("  ")
            w.beginObject()
            w.name("passed").value(passed)
            w.name("startedAt").value(startedAt)
            w.name("scale").value(scale.name)
            w.name("generationMs").value(generationMs)
            w.name("dbSizeBytes").value(dbSizeBytes)
            w.name("device").beginObject()
            w.name("manufacturer").value(device.manufacturer)
            w.name("model").value(device.model)
            w.name("sdkInt").value(device.sdkInt.toLong())
            w.name("release").value(device.release)
            w.name("totalRamMb").value(device.totalRamMb)
            w.name("lowRamDevice").value(device.lowRamDevice)
            w.name("memoryClassMb").value(device.memoryClassMb.toLong())
            w.name("sqliteVersion").value(device.sqliteVersion)
            w.name("appVersion").value(device.appVersion)
            w.name("abi").value(device.abi)
            w.endObject()
            w.name("counts").beginObject()
            for ((k, v) in counts) w.name(k).value(v)
            w.endObject()
            w.name("results").beginArray()
            for (r in results) {
                w.beginObject()
                w.name("id").value(r.id)
                w.name("budgetMs").value(r.budgetMs)
                w.name("n").value(r.n.toLong())
                w.name("minMs").value(r.minMs)
                w.name("p50Ms").value(r.p50Ms)
                w.name("p95Ms").value(r.p95Ms)
                w.name("p99Ms").value(r.p99Ms)
                w.name("maxMs").value(r.maxMs)
                w.name("pass").value(r.pass)
                w.endObject()
            }
            w.endArray()
            w.name("plans").beginArray()
            for (p in plans) {
                w.beginObject()
                w.name("name").value(p.name)
                w.name("plan").beginArray()
                for (line in p.plan) w.value(line)
                w.endArray()
                w.name("violations").beginArray()
                for (v in p.violations) w.value(v)
                w.endArray()
                w.endObject()
            }
            w.endArray()
            w.endObject()
        }
        return out.toString()
    }
}
