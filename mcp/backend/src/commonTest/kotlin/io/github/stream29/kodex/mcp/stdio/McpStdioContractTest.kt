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
import kotlinx.coroutines.*
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.readByteArray
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        assertEquals(1, session.closeAndJoinCount)
        assertTrue(session.cleanupFinished.isCompleted)
    }

    for (kind in listOf("failure", "caller cancellation")) {
        test("constructor $kind awaits exact raw rollback before rethrowing primary") {
            assertConstructorRollback(cancelCaller = kind == "caller cancellation", cleanupKind = null)
        }
        test("constructor $kind keeps primary and one later cleanup failure") {
            for (cleanupKind in listOf("io", "cancellation", "exact primary", "already suppressed")) {
                assertConstructorRollback(cancelCaller = kind == "caller cancellation", cleanupKind = cleanupKind)
            }
        }
    }
}

private suspend fun assertConstructorRollback(cancelCaller: Boolean, cleanupKind: String?) = coroutineScope {
    val caller = Job(coroutineContext[Job])
    val clientOwner = Job(coroutineContext[Job])
    val primary: Throwable = if (cancelCaller) CancellationException("constructor caller cancelled")
        else IllegalStateException("constructor rejected raw stdout")
    val cleanup = when (cleanupKind) {
        null -> null
        "exact primary" -> primary
        "cancellation" -> CancellationException("raw cleanup cancelled")
        "io" -> IOException("later raw cleanup failure")
        else -> IllegalStateException("later raw cleanup failure")
    }
    if (cleanupKind == "already suppressed") primary.addSuppressed(checkNotNull(cleanup))
    val release = CompletableDeferred<Unit>()
    val session = RecordingRawSession(cleanupGate = release, cleanupFailure = cleanup)
    val rejectedSession: ProcessSession = object : ProcessSession by session {
        override val stdout: CoroutineRawSource
            get() {
                if (cancelCaller) caller.cancel(primary as CancellationException)
                throw primary
            }
    }
    val client: ProcessClient = object : ProcessClient {
        override val coroutineContext: CoroutineContext = clientOwner
        override suspend fun start(command: ProcessCommand): ProcessSession = rejectedSession
        override fun close(): Unit = error("Rollback must not close the borrowed process client")
    }
    // Observe the exception as a value inside the original caller, avoiding
    // cancellation/Deferred stack-recovery copies in the identity assertion.
    val observed = CompletableDeferred<Throwable>()
    var construction: Job? = null
    try {
        val operation = CoroutineScope(coroutineContext + caller).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                client.openMcpStdioTransport(McpServerConfiguration.Stdio(command = "sentinel"))
                observed.complete(AssertionError("Rejected constructor returned a transport"))
            } catch (failure: Throwable) {
                observed.complete(failure)
            }
        }
        construction = operation
        withTimeout(5.seconds) { session.closeAndJoinEntered.await() }
        assertEquals(1, session.closeAndJoinCount, "Synchronous close is not exact-session await")
        assertEquals(1, session.closeCount)
        assertFalse(session.cleanupFinished.isCompleted)
        assertFalse(observed.isCompleted, "Constructor rethrew before exact raw rollback completed")
        assertFalse(operation.isCompleted)
        assertEquals(!cancelCaller, caller.isActive)
        assertTrue(clientOwner.isActive, "Rollback cancelled the borrowed client")

        release.complete(Unit)
        assertSame(primary, withTimeout(5.seconds) { observed.await() })
        withTimeout(5.seconds) { operation.join() }
        assertTrue(session.cleanupFinished.isCompleted)
        assertEquals(1, session.closeAndJoinCount)
        assertEquals(1, session.closeCount)
        if (cleanup == null || cleanup === primary) {
            assertTrue(primary.suppressedExceptions.isEmpty(), "Exact primary must not suppress itself")
        } else {
            assertEquals(1, primary.suppressedExceptions.size)
            assertSame(cleanup, primary.suppressedExceptions.single())
        }
        assertTrue(clientOwner.isActive)
    } finally {
        release.complete(Unit)
        withContext(NonCancellable) {
            withTimeout(5.seconds) {
                construction?.cancelAndJoin()
                caller.cancelAndJoin()
                clientOwner.cancelAndJoin()
            }
        }
    }
}

private class RecordingRawSession(
    private val cleanupGate: CompletableDeferred<Unit>? = null,
    private val cleanupFailure: Throwable? = null,
) : ProcessSession {
    val written = CompletableDeferred<String>()
    val closeAndJoinEntered = CompletableDeferred<Unit>()
    val cleanupFinished = CompletableDeferred<Unit>()
    private var pendingFrame = ""
    var closeCount = 0
    var closeAndJoinCount = 0
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
    override suspend fun closeAndJoin() {
        closeAndJoinCount++
        close()
        closeAndJoinEntered.complete(Unit)
        cleanupGate?.await()
        cleanupFinished.complete(Unit)
        cleanupFailure?.let { throw it }
    }
}

private fun suspendedSource(): CoroutineRawSource = object : CoroutineRawSource {
    override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long = awaitCancellation()
    override suspend fun close(): Unit = Unit
}
