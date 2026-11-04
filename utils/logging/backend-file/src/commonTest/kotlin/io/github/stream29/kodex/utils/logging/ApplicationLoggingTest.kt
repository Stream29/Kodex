package io.github.stream29.kodex.utils.logging

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.oshai.kotlinlogging.KMarkerFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import co.touchlab.kermit.Severity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.io.RawSink
import kotlinx.io.files.SystemFileSystem
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private suspend fun temporaryKodexHome(): Path =
    Path(Path(Path("build"), "tmp"), "kodex-logging-${Random.nextLong()}").also {
        SystemCoroutineFileSystem.createDirectories(it)
    }

val applicationLoggingTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("logging in runBlocking completion callbacks never reenters its event loop") {
        val home = temporaryKodexHome()
        val writer = BackgroundRollingFileLogWriter(rollingFileLogWriterConfig(home))
        try {
            runBlocking {
                var dispatched = false
                val queued = launch { dispatched = true }
                val completed = Job()
                completed.invokeOnCompletion {
                    repeat(20_000) { writer.log(Severity.Info, "completion-$it", "test", null) }
                    assertFalse(dispatched, "Logging dispatched queued work inside a completion callback.")
                }
                completed.complete()
                assertFalse(dispatched)
                queued.join()
            }
        } finally { writer.close() }
    }
    test("close drains queued events to disk and is repeatable") {
        val home = temporaryKodexHome()
        val session = initializeLogging(home)
        val logger = KotlinLogging.logger("drain")
        repeat(2_000) { logger.info { "drain-event-$it" } }
        session.close()
        session.close()
        val text = SystemCoroutineFileSystem.readString(Path(home, "log/Kodex.log"))
        assertEquals(2_000, text.lineSequence().count { "drain-event-" in it })
        assertTrue(text.contains("drain-event-1999"))
    }
    test("rolling retains five files with the existing numeric sibling names") {
        val home = temporaryKodexHome()
        val writer = BackgroundRollingFileLogWriter(RollingLogConfig("Backend-123", home, 128, 5))
        repeat(20) { writer.log(Severity.Info, "event-$it " + "x".repeat(180), "roll", null) }
        writer.close()
        assertEquals(setOf("Backend-123.log") + (1..4).map { "Backend-123-$it.log" },
            SystemCoroutineFileSystem.list(home).map { it.name }.toSet())
        assertTrue(SystemCoroutineFileSystem.readString(Path(home, "Backend-123.log")).contains("event-19"))
        assertTrue(SystemCoroutineFileSystem.readString(Path(home, "Backend-123-4.log")).contains("event-15"))
    }
    test("sink failure cannot strand log producers or close") {
        val home = temporaryKodexHome()
        SystemCoroutineFileSystem.createDirectories(Path(home, "Kodex.log"))
        val writer = BackgroundRollingFileLogWriter(rollingFileLogWriterConfig(home))
        repeat(2_000) { writer.log(Severity.Info, "failed-sink-$it", "test", null) }
        withTimeout(3.seconds) { writer.close(); writer.close() }
        writer.log(Severity.Info, "after-close", "test", null)
    }
    test("close returns within its deadline when background flush is stalled") {
        val home = temporaryKodexHome()
        val flushing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val writer = BackgroundRollingFileLogWriter(rollingFileLogWriterConfig(home)) { path ->
            val sink = SystemFileSystem.sink(path, append = true)
            object : RawSink by sink {
                override fun flush() {
                    flushing.complete(Unit)
                    runBlocking { release.await() }
                    sink.flush()
                }
                override fun close() {
                    try { sink.close() } finally { closed.complete(Unit) }
                }
            }
        }
        try {
            writer.log(Severity.Info, "stalled flush", "test", null)
            withTimeout(3.seconds) { flushing.await() }
            withTimeout(3.seconds) { writer.close() }
            assertFalse(closed.isCompleted)
        } finally {
            release.complete(Unit)
            withTimeout(3.seconds) { closed.await() }
        }
    }
    testFixture { temporaryKodexHome() } asParameterForEach {
        test("initializes direct logging and writes mapped events to the rolling file") { kodexHome ->
            val session = initializeLogging(kodexHome)

            val config = rollingFileLogWriterConfig(Path(kodexHome, LogDirectoryName))
            assertEquals(LogFileBaseName, config.logFileName)
            assertEquals(RollOnSizeBytes, config.rollOnSize)
            assertEquals(MaxLogFiles, config.maxLogFiles)
            assertFalse(io.github.oshai.kotlinlogging.KotlinLoggingConfiguration.logStartupMessage)
            assertEquals(
                io.github.oshai.kotlinlogging.DirectLoggerFactory,
                io.github.oshai.kotlinlogging.KotlinLoggingConfiguration.loggerFactory,
            )

            io.github.oshai.kotlinlogging.KotlinLoggingConfiguration.direct.logLevel = Level.TRACE
            val logger = KotlinLogging.logger("mcp.client")
            listOf(
                Level.TRACE to "level-trace",
                Level.DEBUG to "level-debug",
                Level.INFO to "level-info",
                Level.ERROR to "level-error",
                Level.OFF to "level-off",
            ).forEach { (level, message) ->
                logger.at(level) { this.message = message }
            }
            logger.at(Level.INFO) {
                message = "plain-payload-event"
                payload = mapOf("source" to "independent-file-sink")
            }
            logger.at(
                level = Level.WARN,
                marker = KMarkerFactory.getMarker("transport"),
            ) {
                message = "connection closed"
                cause = IllegalStateException("test failure")
                payload = mapOf("server" to "local")
            }

            session.close()
            val logFile = Path(kodexHome, "$LogDirectoryName/$LogFileBaseName.log")
            val text = withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(5.seconds) {
                    var current = ""
                    while (!current.contains("connection closed")) {
                        current = if (SystemCoroutineFileSystem.exists(logFile)) {
                            SystemCoroutineFileSystem.readString(logFile)
                        } else {
                            ""
                        }
                        if (!current.contains("connection closed")) delay(10.milliseconds)
                    }
                    current
                }
            }
            assertTrue(text.contains("Verbose:"))
            assertTrue(text.contains("level-trace"))
            assertTrue(text.contains("Debug:"))
            assertTrue(text.contains("level-debug"))
            assertTrue(text.contains("Info:"))
            assertTrue(text.contains("level-info"))
            assertTrue(text.contains("Warn:"))
            assertTrue(text.contains("Error:"))
            assertTrue(text.contains("level-error"))
            assertFalse(text.contains("level-off"))
            assertTrue(text.contains("mcp.client"))
            assertTrue(
                text.contains(
                    "plain-payload-event {source=independent-file-sink}",
                ),
            )
            assertTrue(text.contains("[transport] connection closed"))
            assertTrue(text.contains("server=local"))
            assertTrue(text.contains("IllegalStateException"))
            assertTrue(text.contains("test failure"))
        }
    }
}
