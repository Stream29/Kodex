package io.github.stream29.codex.lite.utils.logging

import de.infix.testBalloon.framework.core.testSuite
import io.github.oshai.kotlinlogging.KMarkerFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private suspend fun temporaryCodexLiteHome(): Path =
    Path(Path(Path("build"), "tmp"), "codex-lite-logging-${Random.nextLong()}").also {
        SystemCoroutineFileSystem.createDirectories(it)
    }

public val applicationLoggingTest by testSuite {
    // RollingFileLogWriter owns its file for the test process lifetime, so Windows
    // cannot remove it during fixture teardown. Keep it in Gradle's build output.
    testFixture { temporaryCodexLiteHome() } asParameterForEach {
        test("initializes direct logging and writes mapped events to the rolling file") { codexLiteHome ->
            initializeLogging(codexLiteHome)

            val config = rollingFileLogWriterConfig(Path(codexLiteHome, LogDirectoryName))
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
            logger.at(
                level = Level.WARN,
                marker = KMarkerFactory.getMarker("transport"),
            ) {
                message = "connection closed"
                cause = IllegalStateException("test failure")
                payload = mapOf("server" to "local")
            }

            val logFile = Path(codexLiteHome, "$LogDirectoryName/$LogFileBaseName.log")
            val text = withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(5.seconds) {
                    var current = ""
                    while (!current.contains("connection closed")) {
                        current = if (SystemCoroutineFileSystem.exists(logFile)) {
                            SystemCoroutineFileSystem.readString(logFile)
                        } else {
                            ""
                        }
                        if (!current.contains("connection closed")) delay(10)
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
            assertTrue(text.contains("[transport] connection closed"))
            assertTrue(text.contains("server=local"))
            assertTrue(text.contains("IllegalStateException"))
            assertTrue(text.contains("test failure"))
        }
    }
}
