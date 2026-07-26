package io.github.stream29.codex.lite.utils.processclient

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

internal expect val interactiveProcessCommand: ProcessCommand
internal expect val delayedProcessCommand: ProcessCommand

val processClientIoTest by testSuite(
    compartment = { TestCompartment.RealTime },
) {
    test("exchanges raw bytes with a real direct child process") {
        val client = CoroutineScope(currentCoroutineContext()).ProcessClient()
        val process = client.start(interactiveProcessCommand)
        try {
            process.stdin.buffered().apply {
                writeString("hello from process client\n")
                close()
            }
            val (output, error) = coroutineScope {
                val output = async(Dispatchers.Default) { process.stdout.readText() }
                val error = async(Dispatchers.Default) { process.stderr.readText() }
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
}

private fun RawSource.readText(): String {
    val source = buffered()
    return try {
        source.readString()
    } finally {
        source.close()
    }
}
