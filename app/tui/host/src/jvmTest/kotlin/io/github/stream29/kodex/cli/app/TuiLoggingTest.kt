package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.backend.backendProcessAlive

import de.infix.testBalloon.framework.core.testSuite
import io.github.oshai.kotlinlogging.KMarkerFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.osenvironment.processId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val tuiLoggingTest by testSuite {
    test("TUI writes local diagnostics and retains process logs without backend scopes") {
        // The rolling writer owns its file until process exit, including on Windows.
        val home = Path("build/tmp/tui-logging-${Random.nextLong()}")
        val directory = Path(home, "log")
        SystemCoroutineFileSystem.createDirectories(directory)
        repeat(10) { offset ->
            val name = "Tui-${9_000_000 + offset}"
            SystemCoroutineFileSystem.writeString(Path(directory, "$name.log"), "base")
            SystemCoroutineFileSystem.writeString(Path(directory, "$name-1.log"), "rolled")
        }
        val live = Path(directory, "Backend-${processId()}-1.log")
        val legacy = Path(directory, "Kodex-1.log")
        val foreign = Path(directory, "user-note.txt")
        val startup = Path(directory, "backend-startup.log")
        listOf(live, legacy, foreign, startup).forEach { SystemCoroutineFileSystem.writeString(it, "preserved") }

        initializeTuiLogging(home, ::backendProcessAlive)
        assertEquals(Level.INFO, KotlinLoggingConfiguration.direct.logLevel)
        assertFalse(KotlinLoggingConfiguration.logStartupMessage)
        assertEquals(10L * 1024L * 1024L, TuiLogRollOnSizeBytes)
        assertEquals(5, TuiMaxLogFiles)
        val logger = KotlinLogging.logger("tui.local")
        logger.debug { "hidden-debug-event" }
        KotlinLoggingConfiguration.direct.logLevel = Level.TRACE
        logger.trace { "tui-trace-event" }
        logger.debug { "tui-debug-event" }
        logger.info { "tui-info-event" }
        logger.at(Level.OFF) { message = "disabled-event" }
        logger.at(Level.WARN, marker = KMarkerFactory.getMarker("ui")) {
            message = "local-warning"
            payload = mapOf("view" to "settings")
        }
        CliCoroutineExceptionLogger.handleException(EmptyCoroutineContext, IllegalStateException("local-failure"))
        val logFile = Path(directory, "Tui-${processId()}.log")
        val text = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5.seconds) {
                var contents = ""
                while (!contents.contains("local-failure")) {
                    contents = if (SystemCoroutineFileSystem.exists(logFile)) SystemCoroutineFileSystem.readString(logFile) else ""
                    if (!contents.contains("local-failure")) delay(10)
                }
                contents
            }
        }
        assertTrue(text.contains("Verbose:"))
        assertTrue(text.contains("tui-trace-event"))
        assertTrue(text.contains("Debug:"))
        assertTrue(text.contains("tui-debug-event"))
        assertTrue(text.contains("Info:"))
        assertTrue(text.contains("tui-info-event"))
        assertTrue(text.contains("Warn:"))
        assertTrue(text.contains("[ui] local-warning {view=settings}"))
        assertTrue(text.contains("Unhandled CLI coroutine failure"))
        assertTrue(text.contains("IllegalStateException"))
        assertFalse(text.contains("hidden-debug-event"))
        assertFalse(text.contains("disabled-event"))
        assertFalse(text.contains("scope=global"))
        val inactive = SystemCoroutineFileSystem.list(directory).filter { it.name.startsWith("Tui-900000") }
        assertEquals(16, inactive.size)
        assertEquals(8, inactive.count { it.name.endsWith("-1.log") })
        listOf(live, legacy, foreign, startup).forEach { assertEquals("preserved", SystemCoroutineFileSystem.readString(it)) }
    }
}
