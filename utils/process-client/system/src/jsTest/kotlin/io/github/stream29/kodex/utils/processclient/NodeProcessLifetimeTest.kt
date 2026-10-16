@file:Suppress("UnsafeCastFromDynamic")

package io.github.stream29.kodex.utils.processclient

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.kotlinxiocoroutines.readBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private val fixtureNodeExecutable: String = js("process.execPath")

val nodeProcessLifetimeTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("explicit close owns process close and invalidates partially read raw output") {
        withNodeClient { owner, client ->
            val process = client.start(nodeFixture(
                "process.stdout.write(process.pid + '\\nretained-output'); setInterval(() => {}, 1000);",
            ))
            val pid = withTimeout(5.seconds) { process.stdout.readFixturePid() }
            val guard = client.coroutineContext[Job]!!.children.single()
            assertTrue(fixturePidIsAlive(pid))
            process.close()
            process.close()
            // These must reject immediately, not return retained chunks or EOF.
            assertFailsWith<IllegalStateException> { process.stdout.readAtMostTo(Buffer(), 1) }
            assertFailsWith<IllegalStateException> { process.stderr.readAtMostTo(Buffer(), 0) }
            withTimeout(12.seconds) { guard.join() }
            assertTrue(process.exitCode.isCompleted, "Session owner finished before child close callback")
            assertFalse(fixturePidIsAlive(pid))
            assertTrue(owner.isActive)
            assertTrue(client.coroutineContext[Job]!!.isActive)
        }
    }

    test("parent cancel and join includes real process and all stream cleanup") {
        withNodeClient { owner, client ->
            val process = client.start(nodeFixture(
                "process.stdout.write(process.pid + '\\n'); setInterval(() => {}, 1000);",
            ))
            val pid = withTimeout(5.seconds) { process.stdout.readFixturePid() }
            val clientJob = client.coroutineContext[Job]!!
            assertTrue(clientJob.children.any())
            owner.cancel()
            // Parent invalidation must be visible before the cleanup callback
            // gets another event-loop turn, even with a retained current chunk.
            assertFailsWith<IllegalStateException> { process.stdout.readAtMostTo(Buffer(), 1) }
            withTimeout(12.seconds) { owner.cancelAndJoin() }
            assertTrue(process.exitCode.isCompleted)
            assertFalse(fixturePidIsAlive(pid))
            assertTrue(clientJob.children.none())
            assertFailsWith<IllegalStateException> { process.stdout.readAtMostTo(Buffer(), 1) }
            assertFailsWith<IllegalStateException> { process.stderr.readAtMostTo(Buffer(), 1) }
            assertFailsWith<IllegalStateException> { process.stdin.flush() }
        }
    }

    test("natural EOF permits drain until session close then rejects further reads") {
        withNodeClient { owner, client ->
            val process = client.start(nodeFixture(
                "process.stdout.write('abcdef'); process.stderr.write('error');",
            ))
            val (output, error) = withTimeout(5.seconds) {
                coroutineScope {
                    val output = async { process.stdout.readBytes().decodeToString() }
                    val error = async { process.stderr.readBytes().decodeToString() }
                    output.await() to error.await()
                }
            }
            assertEquals("abcdef", output)
            assertEquals("error", error)
            assertEquals(0, withTimeout(5.seconds) { process.exitCode.await() })
            // The original client still owns the sources after natural exit.
            val guard = client.coroutineContext[Job]!!.children.single()
            process.close()
            withTimeout(5.seconds) { guard.join() }
            assertFailsWith<IllegalStateException> { process.stdout.readAtMostTo(Buffer(), 0) }
            assertFailsWith<IllegalStateException> { process.stderr.readAtMostTo(Buffer(), 0) }
            assertTrue(owner.isActive)
        }
    }

    test("cancelled spawn return closes unclaimed child while original client remains active") {
        withNodeClient { owner, client ->
            coroutineScope {
                val returns = ReturnGateDispatcher()
                val caller = async(returns) {
                    client.start(nodeFixture("setInterval(() => {}, 1000);"))
                }
                withTimeout(5.seconds) { returns.pending.receive() }.run() // Enter start, suspend on spawn.
                val returnFromSpawn = withTimeout(5.seconds) { returns.pending.receive() }
                assertTrue(client.coroutineContext[Job]!!.children.any())
                caller.cancel()
                // Resume the *already acquired* resource handoff only after cancellation.
                val pump = launch(start = CoroutineStart.UNDISPATCHED) {
                    returnFromSpawn.run()
                    for (continuation in returns.pending) continuation.run()
                }
                try {
                    withTimeout(12.seconds) { caller.join() }
                    assertTrue(client.coroutineContext[Job]!!.children.none(),
                        "Cancelled startup left its resource guard running")
                    assertTrue(client.coroutineContext[Job]!!.isActive)
                    assertTrue(owner.isActive)
                } finally {
                    caller.cancel()
                    withContext(NonCancellable) { withTimeout(12.seconds) { caller.join() } }
                    returns.pending.close()
                    pump.join()
                }
            }
        }
    }

    test("failed executable startup releases its registered resource guard") {
        withNodeClient { owner, client ->
            assertFailsWith<ProcessException> {
                client.start(ProcessCommand("/kodex-test-fixture-does-not-exist/executable"))
            }
            assertTrue(client.coroutineContext[Job]!!.children.none())
            assertTrue(owner.isActive)
        }
    }
}

private fun nodeFixture(script: String) =
    ProcessCommand(fixtureNodeExecutable, listOf("-e", script))

private suspend fun withNodeClient(block: suspend (Job, ProcessClient) -> Unit) {
    val owner = Job()
    val client = CoroutineScope(owner).ProcessClient()
    try {
        block(owner, client)
    } finally {
        client.close()
        withContext(NonCancellable) { withTimeout(12.seconds) { owner.cancelAndJoin() } }
    }
}

private suspend fun io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSource.readFixturePid(): Int {
    val line = StringBuilder()
    val bytes = Buffer()
    while (true) {
        check(readAtMostTo(bytes, 1) == 1L) { "Fixture exited before publishing PID" }
        val byte = bytes.readByteArray(1)[0].toInt().toChar()
        if (byte == '\n') return line.toString().toInt()
        line.append(byte)
    }
}

/** Queries only a PID published by this test's direct child. */
private fun fixturePidIsAlive(pid: Int): Boolean = js("""
    (() => {
        try { process.kill(pid, 0); return true; }
        catch (e) { if (e.code === 'ESRCH') return false; throw e; }
    })()
""")

private class ReturnGateDispatcher : CoroutineDispatcher() {
    val pending = Channel<Runnable>(Channel.UNLIMITED)
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        check(pending.trySend(block).isSuccess)
    }
}
