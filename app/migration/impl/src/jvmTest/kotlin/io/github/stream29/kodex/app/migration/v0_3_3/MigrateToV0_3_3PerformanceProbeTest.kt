package io.github.stream29.kodex.app.migration.v0_3_3

import io.github.stream29.kodex.agentstorage.filesystemlayout.recordPath
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ProbeEnabledEnvironmentVariable: String =
    "KODEX_PATCH_MIGRATION_PERFORMANCE_PROBE"
private const val ProbeScanRecordCountEnvironmentVariable: String =
    "KODEX_PATCH_MIGRATION_SCAN_RECORD_COUNT"
private const val ProbeRewriteRecordCountEnvironmentVariable: String =
    "KODEX_PATCH_MIGRATION_REWRITE_RECORD_COUNT"
private const val ProbeSnapshotBytesEnvironmentVariable: String =
    "KODEX_PATCH_MIGRATION_SNAPSHOT_BYTES"
private const val ProbeReportEnvironmentVariable: String =
    "KODEX_PATCH_MIGRATION_REPORT"
private const val DefaultScanRecordCount: Int = 10_000
private const val DefaultRewriteRecordCount: Int = 100
private const val DefaultSnapshotBytes: Int = 5 * 1024 * 1024
private const val BytesPerMebibyte: Double = 1024.0 * 1024.0

/**
 * Manual probe for release-time migration verification.
 *
 * Run only this probe with:
 *
 * `KODEX_PATCH_MIGRATION_PERFORMANCE_PROBE=1 ./gradlew
 * :app-migration-impl:jvmTest --rerun-tasks --tests
 * io.github.stream29.kodex.app.migration.v0_3_3.MigrateToV0_3_3PerformanceProbeTest`
 */
class MigrateToV0_3_3PerformanceProbeTest {
    @Test
    fun measureScanRewriteAndPeakHeap() = runBlocking {
        if (System.getenv(ProbeEnabledEnvironmentVariable) != "1") {
            println("Set $ProbeEnabledEnvironmentVariable=1 to run the patch migration probe.")
            return@runBlocking
        }

        val scanRecordCount = positiveEnvironmentInt(
            name = ProbeScanRecordCountEnvironmentVariable,
            default = DefaultScanRecordCount,
        )
        val rewriteRecordCount = positiveEnvironmentInt(
            name = ProbeRewriteRecordCountEnvironmentVariable,
            default = DefaultRewriteRecordCount,
        )
        val snapshotBytes = positiveEnvironmentInt(
            name = ProbeSnapshotBytesEnvironmentVariable,
            default = DefaultSnapshotBytes,
        )
        val scanHome = temporaryDirectory("scan")
        val rewriteHome = temporaryDirectory("rewrite")
        try {
            val scanWork = createWorkTimeline(scanHome)
            repeat(scanRecordCount) { index ->
                SystemCoroutineFileSystem.writeString(
                    recordPath(scanWork, index),
                    """{"type":"reasoning","item":{"id":"$index"}}""",
                )
            }
            val scan = measureMigration(scanHome)

            val rewriteWork = createWorkTimeline(rewriteHome)
            val legacyEvent = legacyPatchEvent(snapshotBytes)
            repeat(rewriteRecordCount) { index ->
                SystemCoroutineFileSystem.writeBytes(
                    recordPath(rewriteWork, index),
                    legacyEvent,
                )
            }
            val rewrite = measureMigration(rewriteHome)
            val migrated = SystemCoroutineFileSystem.readString(recordPath(rewriteWork, 0))
            assertFalse("\"delta\":" in migrated)
            assertTrue(migrated.length < 1_000)

            val rewrittenMebibytes =
                legacyEvent.size.toDouble() * rewriteRecordCount / BytesPerMebibyte
            val report = buildString {
                appendLine("apply_patch migration 0.3.3 performance probe")
                appendLine("scan_records=$scanRecordCount")
                appendLine("rewrite_records=$rewriteRecordCount")
                appendLine("legacy_record_mib=${format(legacyEvent.size / BytesPerMebibyte)}")
                appendLine("scan_seconds=${format(scan.seconds)}")
                appendLine(
                    "scan_records_per_second=${format(scanRecordCount / scan.seconds)}",
                )
                appendLine("scan_peak_heap_growth_mib=${format(scan.peakHeapGrowthMebibytes)}")
                appendLine("rewrite_seconds=${format(rewrite.seconds)}")
                appendLine(
                    "rewrite_records_per_second=${format(rewriteRecordCount / rewrite.seconds)}",
                )
                appendLine(
                    "rewrite_mib_per_second=${format(rewrittenMebibytes / rewrite.seconds)}",
                )
                appendLine("rewrite_peak_heap_growth_mib=${format(rewrite.peakHeapGrowthMebibytes)}")
            }
            System.getenv(ProbeReportEnvironmentVariable)
                ?.takeIf(String::isNotBlank)
                ?.let { reportPath ->
                    val path = Paths.get(reportPath)
                    path.parent?.let(Files::createDirectories)
                    Files.writeString(path, report)
                }
            println(report)
        } finally {
            deleteRecursively(scanHome)
            deleteRecursively(rewriteHome)
        }
    }
}

private suspend fun measureMigration(home: Path): MigrationMeasurement = coroutineScope {
    System.gc()
    delay(100)
    val memory = ManagementFactory.getMemoryMXBean()
    val baseline = memory.heapMemoryUsage.used
    val peak = AtomicLong(baseline)
    val running = AtomicBoolean(true)
    val sampler = launch(Dispatchers.Default) {
        while (running.get()) {
            peak.accumulateAndGet(memory.heapMemoryUsage.used, ::maxOf)
            delay(5)
        }
    }
    val elapsedNanoseconds = try {
        measureNanoTime {
            migrateToV0_3_3(home, SystemCoroutineFileSystem)
        }
    } finally {
        running.set(false)
        sampler.cancelAndJoin()
    }
    MigrationMeasurement(
        seconds = elapsedNanoseconds / 1_000_000_000.0,
        peakHeapGrowthMebibytes = (peak.get() - baseline).coerceAtLeast(0) / BytesPerMebibyte,
    )
}

private suspend fun createWorkTimeline(home: Path): Path =
    Path(home, "sessions", "0", "work").also {
        SystemCoroutineFileSystem.createDirectories(it)
    }

private fun legacyPatchEvent(snapshotBytes: Int): ByteArray =
    buildString {
        append(
            """{"type":"patch_tool_event","call_id":"probe","diff":{"patch":"patch","hunks":[]},"result":{"type":"success","apply_result":{"affected_paths":{"added":[],"modified":["large.txt"],"deleted":[]},"delta":{"changes":[{"path":"large.txt","change":{"type":"update","move_path":null,"old_content":"""",
        )
        repeat(snapshotBytes) { append('x') }
        append(
            """","overwritten_move_content":null,"new_content":"new"}}],"exact":true}}}}""",
        )
    }.encodeToByteArray()

private fun positiveEnvironmentInt(name: String, default: Int): Int =
    System.getenv(name)
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
        ?: default

private suspend fun temporaryDirectory(label: String): Path =
    Path(
        SystemTemporaryDirectory,
        "kodex-migration-0.3.3-$label-${Random.nextLong()}",
    ).also {
        SystemCoroutineFileSystem.createDirectories(it)
    }

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { child -> deleteRecursively(child) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}

private fun format(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

private data class MigrationMeasurement(
    val seconds: Double,
    val peakHeapGrowthMebibytes: Double,
)
