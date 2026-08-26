package io.github.stream29.kodex.utils.processclient

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSource
import io.github.stream29.kodex.utils.kotlinxiocoroutines.readBytes
import io.github.stream29.kodex.utils.kotlinxiocoroutines.use
import io.github.stream29.kodex.utils.kotlinxiocoroutines.writeBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

internal expect val interactiveProcessCommand: ProcessCommand
internal expect val delayedProcessCommand: ProcessCommand
internal expect val environmentProcessCommand: ProcessCommand

val processClientIoTest by testSuite(
    compartment = { TestCompartment.RealTime },
) {
    test("exchanges raw bytes with a real direct child process") {
        val client = CoroutineScope(currentCoroutineContext()).ProcessClient()
        val process = client.start(interactiveProcessCommand)
        try {
            process.stdin.apply {
                writeBytes("hello from process client\n".encodeToByteArray())
                close()
            }
            val (output, error) = coroutineScope {
                val output = async { process.stdout.readText() }
                val error = async { process.stderr.readText() }
                output.await() to error.await()
            }

            assertEquals(0, withTimeout(5.seconds) { process.exitCode.await() })
            assertTrue("out=hello from process client" in output, output)
            assertTrue("err=hello from process client" in error, error)
        } finally {
            process.close()
            client.close()
        }
    }

    test("closing a client terminates its real child process") {
        val client = CoroutineScope(currentCoroutineContext()).ProcessClient()
        val process = client.start(delayedProcessCommand)

        client.close()

        assertTrue(withTimeout(5.seconds) { process.exitCode.await() } != 0)
    }

    test("overlays configured environment variables on a real child process") {
        val client = CoroutineScope(currentCoroutineContext()).ProcessClient()
        val process = client.start(environmentProcessCommand)
        try {
            process.stdin.close()
            val output = withTimeout(5.seconds) { process.stdout.readText() }

            assertEquals(0, withTimeout(5.seconds) { process.exitCode.await() })
            assertEquals(TestEnvironmentValue, output.trim())
        } finally {
            process.close()
            client.close()
        }
    }
}

private suspend fun CoroutineRawSource.readText(): String =
    use { source -> source.readBytes().decodeToString() }

internal const val TestEnvironmentName: String = "KODEX_PROCESS_CLIENT_TEST"
internal const val TestEnvironmentValue: String = "configured-environment"
