package io.github.stream29.kodex.integrationtest

import de.infix.testBalloon.framework.core.testSuite
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.logging.agent
import io.github.stream29.kodex.utils.logging.global
import io.github.stream29.kodex.utils.logging.initializeLogging
import io.github.stream29.kodex.utils.logging.session
import io.github.stream29.kodex.utils.logging.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlin.random.Random
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val backendLoggingCompositionTest by testSuite {
    test("independent backend scope and file libraries compose through KotlinLogging") {
        // Keep the rolling writer's open file under build output until process exit.
        val home = Path("build/tmp/backend-logging-composition-${Random.nextLong()}")
        initializeLogging(home)
        KotlinLogging.logger("mcp.client")
            .global()
            .session("session-1")
            .agent()
            .tool("mcp__local.echo", "call-3")
            .info { "scoped-event" }
        val logFile = Path(home, "log/Kodex.log")
        val text = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(5.seconds) {
                var contents = ""
                while (!contents.contains("scoped-event")) {
                    contents = if (SystemCoroutineFileSystem.exists(logFile)) SystemCoroutineFileSystem.readString(logFile) else ""
                    if (!contents.contains("scoped-event")) delay(10)
                }
                contents
            }
        }
        assertTrue(text.contains("mcp.client"))
        assertTrue(text.contains("scoped-event {scope=tool, uri=session-1, tool_name=mcp__local.echo, call_id=call-3}"))
    }
}
