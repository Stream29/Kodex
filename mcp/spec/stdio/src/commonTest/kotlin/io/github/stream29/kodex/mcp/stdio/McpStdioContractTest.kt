package io.github.stream29.kodex.mcp.stdio

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.mcp.contract.McpSecret
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSink
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSource
import io.github.stream29.kodex.utils.processclient.ProcessClient
import io.github.stream29.kodex.utils.processclient.ProcessCommand
import io.github.stream29.kodex.utils.processclient.ProcessException
import io.github.stream29.kodex.utils.processclient.ProcessSession
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.readByteArray
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val mcpStdioContractTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("the sole startup route maps configuration onto the real process spec and raw streams") {
        val session = RecordingRawSession()
        var captured: ProcessCommand? = null
        val client: ProcessClient = object : ProcessClient {
            override val coroutineContext: CoroutineContext = EmptyCoroutineContext
            override suspend fun start(command: ProcessCommand): ProcessSession {
                captured = command
                return session
            }
            override fun close(): Unit = error("Transport must not close the process client.")
        }
        val transport = client.openMcpStdioTransport(
            McpServerConfiguration.Stdio(
                command = "sentinel-executable",
                args = listOf("literal", "$(not-a-shell)"),
                workingDirectory = Path("/sentinel/cwd"),
                environment = mapOf("SENTINEL" to McpSecret("value")),
            ),
        )
        var closeCallbacks = 0
        transport.onClose { closeCallbacks++ }
        try {
            assertEquals(
                ProcessCommand(
                    "sentinel-executable", listOf("literal", "$(not-a-shell)"),
                    Path("/sentinel/cwd"), mapOf("SENTINEL" to "value"),
                ),
                captured,
            )
            transport.start()
            transport.send(JSONRPCNotification(method = "sentinel/notification"))
            val frame = withTimeout(5.seconds) { session.written.await() }
            assertTrue("sentinel/notification" in frame)
            assertTrue(frame.endsWith("\n"))
        } finally {
            transport.close()
            transport.close()
        }
        assertEquals(1, session.closeCount)
        assertEquals(1, closeCallbacks)
        assertTrue(session.flushCount > 0)
    }

    test("real process startup failures pass through without another transport route") {
        val failure = ProcessException("sentinel-startup-failure")
        val client: ProcessClient = object : ProcessClient {
            override val coroutineContext: CoroutineContext = EmptyCoroutineContext
            override suspend fun start(command: ProcessCommand): ProcessSession = throw failure
            override fun close(): Unit = Unit
        }
        val observed = assertFailsWith<ProcessException> {
            client.openMcpStdioTransport(McpServerConfiguration.Stdio(command = "sentinel"))
        }
        assertSame(failure, observed)
    }

    test("transport construction failure closes the already acquired raw session") {
        val failure = IllegalStateException("sentinel-raw-stream")
        val session = RecordingRawSession()
        val rejectedSession: ProcessSession = object : ProcessSession by session {
            override val stdout: CoroutineRawSource
                get() = throw failure
        }
        val client: ProcessClient = object : ProcessClient {
            override val coroutineContext: CoroutineContext = EmptyCoroutineContext
            override suspend fun start(command: ProcessCommand): ProcessSession = rejectedSession
            override fun close(): Unit = error("Construction failure must not close the client.")
        }
        val observed = assertFailsWith<IllegalStateException> {
            client.openMcpStdioTransport(McpServerConfiguration.Stdio(command = "sentinel"))
        }
        assertSame(failure, observed)
        assertEquals(1, session.closeCount)
    }
}

private class RecordingRawSession : ProcessSession {
    val written = CompletableDeferred<String>()
    private var pendingFrame = ""
    var closeCount = 0
    var flushCount = 0
    override val exitCode: Deferred<Int> = CompletableDeferred()
    override val stdin: CoroutineRawSink = object : CoroutineRawSink {
        override suspend fun write(source: Buffer, byteCount: Long) {
            pendingFrame = source.readByteArray(byteCount.toInt()).decodeToString()
        }
        override suspend fun flush() {
            flushCount++
            written.complete(pendingFrame)
        }
        override suspend fun close(): Unit = Unit
    }
    override val stdout: CoroutineRawSource = suspendedSource()
    override val stderr: CoroutineRawSource = suspendedSource()
    override fun close() {
        closeCount++
    }
}

private fun suspendedSource(): CoroutineRawSource = object : CoroutineRawSource {
    override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long = awaitCancellation()
    override suspend fun close(): Unit = Unit
}
