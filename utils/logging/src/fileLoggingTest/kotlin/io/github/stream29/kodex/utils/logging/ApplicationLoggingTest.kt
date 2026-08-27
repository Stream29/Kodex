package io.github.stream29.kodex.utils.logging

import de.infix.testBalloon.framework.core.testSuite
import io.github.oshai.kotlinlogging.KMarkerFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
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

val applicationLoggingTest by testSuite {
    // RollingFileLogWriter owns its file for the test process lifetime, so Windows
    // cannot remove it during fixture teardown. Keep it in Gradle's build output.
    testFixture { temporaryKodexHome() } asParameterForEach {
        test("initializes direct logging and writes mapped events to the rolling file") { kodexHome ->
            initializeLogging(kodexHome)

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
            logger
                .global()
                .session("session-1")
                .agent("agent-2")
                .tool("mcp__local.echo", "call-3")
                .info { "scoped-event" }
            logger.at(
                level = Level.WARN,
                marker = KMarkerFactory.getMarker("transport"),
            ) {
                message = "connection closed"
                cause = IllegalStateException("test failure")
                payload = mapOf("server" to "local")
            }

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
                    "scoped-event " +
                        "{scope=tool, session_id=session-1, agent_id=agent-2, " +
                        "tool_name=mcp__local.echo, call_id=call-3}",
                ),
            )
            assertTrue(text.contains("[transport] connection closed"))
            assertTrue(text.contains("server=local"))
            assertTrue(text.contains("IllegalStateException"))
            assertTrue(text.contains("test failure"))
        }
    }
}
