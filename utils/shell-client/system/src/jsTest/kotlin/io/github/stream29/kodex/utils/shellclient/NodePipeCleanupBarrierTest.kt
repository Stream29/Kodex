@file:Suppress("UnsafeCastFromDynamic")

package io.github.stream29.kodex.utils.shellclient

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSink
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSource
import io.github.stream29.kodex.utils.processclient.ProcessClient
import io.github.stream29.kodex.utils.processclient.ProcessCommand
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.io.Buffer
import kotlinx.io.IOException
import node.os.platform
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

val nodePipeCleanupBarrierTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("session-only cancellation joins real Node release while client stays alive") {
        withRealNodeShellGate { owner, client, session, gate, entered ->
            val job = session.scope.coroutineContext[Job]!!
            val primary = CancellationException("only original Shell session cancelled")
            job.cancel(primary)
            withTimeout(5.seconds) { entered.await() }
            coroutineScope {
                val join = async(start = CoroutineStart.UNDISPATCHED) { job.join() }
                // The actual child has emitted close, but its original raw
                // guard is still waiting for delivery to its registered listener.
                yield()
                assertFalse(join.isCompleted, "Shell session ended before exact raw Node guard")
                assertTrue(owner.isActive)
                assertTrue(client.coroutineContext[Job]!!.isActive)
                assertTrue(gate.streamsDestroyed() as Boolean)
                gate.release()
                withTimeout(5.seconds) { join.await() }
                val failure = assertFailsWith<CancellationException> { session.exitCode.await() }
                assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it === primary })
                assertTrue(gate.delivered() as Boolean)
                assertTrue(client.coroutineContext[Job]!!.isActive)
            }
        }
    }

    test("normal close awaits real Node callback without spurious output failure") {
        withRealNodeShellGate { owner, client, session, gate, entered ->
            session.close()
            session.close()
            withTimeout(5.seconds) { entered.await() }
            assertFalse(session.exitCode.isCompleted)
            assertTrue(gate.streamsDestroyed() as Boolean)
            gate.release()
            withTimeout(5.seconds) {
                assertTrue(session.exitCode.await() != 0, "Terminated real child reported natural success")
                session.scope.coroutineContext[Job]!!.join()
            }
            assertTrue(owner.isActive)
            assertTrue(client.coroutineContext[Job]!!.isActive)
        }
    }

    test("unclaimed Shell handoff awaits exact raw Node release") {
        val owner = Job()
        val scope = CoroutineScope(owner)
        val client = scope.ProcessClient()
        val entered = CompletableDeferred<Unit>()
        val gate = installActualChildCloseGate { entered.complete(Unit) }
        try {
            coroutineScope {
                val returns = NodeShellReturnDispatcher()
                var acquired: PipeProcessSession? = null
                val caller = launch(returns) {
                    scope.acquireShellSession(Dispatchers.Default) {
                        val raw = client.start(ProcessCommand(
                            js("process.execPath") as String,
                            listOf("-e", nodeGateScript(gate.marker as String)),
                        ))
                        PipeProcessSession(raw, scope).also { acquired = it }
                    }
                }
                withTimeout(5.seconds) { returns.pending.receive() }.run()
                val handoff = withTimeout(5.seconds) { returns.pending.receive() }
                assertNotNull(acquired)
                caller.cancel(CancellationException("unclaimed real Node Shell handoff"))
                val pump = launch(start = CoroutineStart.UNDISPATCHED) {
                    handoff.run()
                    for (next in returns.pending) next.run()
                }
                try {
                    withTimeout(5.seconds) { entered.await() }
                    yield()
                    assertFalse(caller.isCompleted, "Rollback dropped the actual asynchronous raw resource")
                    assertTrue(client.coroutineContext[Job]!!.isActive)
                    assertTrue(gate.streamsDestroyed() as Boolean)
                    gate.release()
                    withTimeout(5.seconds) { caller.join() }
                    assertTrue(checkNotNull(acquired).scope.coroutineContext[Job]!!.isCompleted)
                    assertTrue(client.coroutineContext[Job]!!.children.none())
                    assertTrue(owner.isActive)
                } finally {
                    gate.release()
                    withContext(NonCancellable) { caller.cancelAndJoin() }
                    returns.pending.close()
                    pump.join()
                }
            }
        } finally {
            gate.release()
            gate.restore()
            client.close()
            withContext(NonCancellable) { withTimeout(10.seconds) { owner.cancelAndJoin() } }
        }
    }

    test("session-only cancellation joins gated raw cleanup and preserves primary") {
        val owner = Job()
        val rawOwner = Job(owner)
        val primary = CancellationException("original session primary")
        val cleanup = IOException("gated raw cleanup fault")
        val raw = GatedAsyncRawSession(CoroutineScope(rawOwner), cleanup)
        val session = PipeProcessSession(raw, CoroutineScope(owner))
        try {
            coroutineScope {
                val job = session.scope.coroutineContext[Job]!!
                job.cancel(primary)
                withTimeout(5.seconds) { raw.releaseEntered.await() }
                val join = async(start = CoroutineStart.UNDISPATCHED) { job.join() }
                assertFalse(join.isCompleted)
                assertTrue(rawOwner.isActive, "Pipe joined/cancelled the whole raw client")
                raw.allowRelease.complete(Unit)
                withTimeout(5.seconds) { join.await() }
                assertTrue(raw.released)
                assertTrue(primary.suppressedExceptions.any { it === cleanup })
                assertTrue(owner.isActive)
            }
        } finally {
            raw.allowRelease.complete(Unit)
            withContext(NonCancellable) { owner.cancelAndJoin() }
        }
    }
}

private fun nodeGateScript(marker: String): String =
    "/* $marker */ process.stdout.write(\"gate-ready\\n\"); setInterval(() => {}, 1000);"

private suspend fun withRealNodeShellGate(
    block: suspend (Job, ShellClient, ProcessSession, dynamic, CompletableDeferred<Unit>) -> Unit,
) {
    val owner = Job()
    val client = CoroutineScope(owner).ShellClient()
    val entered = CompletableDeferred<Unit>()
    val gate = installActualChildCloseGate { entered.complete(Unit) }
    try {
        val windows = platform().toString() == "win32"
        val executable = js("process.execPath") as String
        val script = nodeGateScript(gate.marker as String)
        val command = if (windows) "& '$executable' -e '$script'"
            else "exec '${executable.replace("'", "'\\''")}' -e '$script'"
        val session = client.start(ShellProcessCommand(
            command,
            shell = checkNotNull(Shell.resolve(if (windows) ShellType.PowerShell else ShellType.Sh)),
            tty = false,
        ))
        withTimeout(5.seconds) {
            val output = StringBuilder()
            while ("gate-ready" !in output) {
                output.append(session.stdout.read(10.milliseconds).renderedBytes().decodeToString())
            }
        }
        block(owner, client, session, gate, entered)
    } finally {
        gate.release()
        gate.restore()
        client.close()
        withContext(NonCancellable) { withTimeout(10.seconds) { owner.cancelAndJoin() } }
    }
}

// Test-only shim on the real ChildProcess event delivery, limited to a unique
// test-owned spawn argument. It does not replace spawn/streams/the raw guard.
// Restore the prototype and deliver a held event in every test finally.
private fun installActualChildCloseGate(onHeld: () -> Unit): dynamic = js("""
    (() => {
        const proto = require('node:child_process').ChildProcess.prototype;
        const original = proto.emit;
        const marker = 'kodex-node-cleanup-gate-fixture-' + Math.random().toString(36).slice(2);
        let child = null, held = null, delivered = false, opened = false;
        function intercepted(event, ...args) {
            const owned = this.spawnargs && this.spawnargs.some(
                arg => String(arg).includes(marker));
            if (owned && child === null) child = this;
            if (this === child && event === 'close' && !opened) {
                held = args;
                onHeld();
                return true;
            }
            return original.call(this, event, ...args);
        }
        proto.emit = intercepted;
        function release() {
            opened = true;
            if (held !== null) {
                const args = held; held = null;
                original.call(child, 'close', ...args);
                delivered = true;
            }
        }
        return {
            marker,
            release,
            delivered: () => delivered,
            streamsDestroyed: () => !!child && child.stdin.destroyed &&
                child.stdout.destroyed && child.stderr.destroyed,
            restore: () => { release(); if (proto.emit === intercepted) proto.emit = original; }
        };
    })()
""")

private class NodeShellReturnDispatcher : CoroutineDispatcher() {
    val pending = Channel<Runnable>(Channel.UNLIMITED)
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        check(pending.trySend(block).isSuccess)
    }
}

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
private class GatedAsyncRawSession(scope: CoroutineScope, private val cleanup: Throwable) :
    io.github.stream29.kodex.utils.processclient.ProcessSession {
    val releaseEntered = CompletableDeferred<Unit>()
    val allowRelease = CompletableDeferred<Unit>()
    var released = false
    private val releaseResult = CompletableDeferred<Result<Unit>>()
    private val guard = scope.launch(start = CoroutineStart.ATOMIC) {
        try { awaitCancellation() } finally {
            withContext(NonCancellable) {
                releaseEntered.complete(Unit)
                allowRelease.await()
                released = true
                exitCode.complete(143)
                releaseResult.complete(Result.failure(cleanup))
            }
        }
    }
    override val exitCode = CompletableDeferred<Int>()
    override val stdin = object : CoroutineRawSink {
        override suspend fun write(source: Buffer, byteCount: Long) = Unit
        override suspend fun flush() = Unit
        override suspend fun close() = Unit
    }
    override val stdout = object : CoroutineRawSource {
        override suspend fun readAtMostTo(sink: Buffer, byteCount: Long): Long = awaitCancellation()
        override suspend fun close() = Unit
    }
    override val stderr: CoroutineRawSource = stdout
    override fun close() { guard.cancel() }
    override suspend fun closeAndJoin() {
        close()
        guard.join()
        releaseResult.await().getOrThrow()
    }
}
