package io.github.stream29.kodex.utils.processclient

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.kotlinxiocoroutines.readBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val windowsProcessIoTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("cmd keeps stderr separate and reports exit before either pipe has been read") {
        val client = CoroutineScope(currentCoroutineContext()).ProcessClient()
        try {
            val child = client.start(windowsCmd("echo out-marker & echo err-marker 1>&2 & exit /b 23"))
            try {
                withTimeout(5.seconds) {
                    assertEquals(23, child.exitCode.await())
                    assertEquals("out-marker", child.stdout.readBytes().decodeToString().trim())
                    assertEquals("err-marker", child.stderr.readBytes().decodeToString().trim())
                }
            } finally {
                child.closeAndJoin()
            }
        } finally {
            client.close()
        }
    }

    test("interleaved children drain both IOCP output pipes independently") {
        val client = CoroutineScope(currentCoroutineContext()).ProcessClient()
        try {
            withTimeout(20.seconds) {
                coroutineScope {
                    (1..4).map { index ->
                        async {
                            val out = "stdout-$index-abcdefghijklmnopqrstuvwxyz"
                            val err = "stderr-$index-abcdefghijklmnopqrstuvwxyz"
                            val child = client.start(windowsCmd(
                                "for /L %i in (1,1,2000) do (echo $out-%i & echo $err-%i 1>&2)",
                            ))
                            try {
                                val stdout = async { child.stdout.readBytes().decodeToString() }
                                val stderr = async { child.stderr.readBytes().decodeToString() }
                                assertEquals(0, child.exitCode.await())
                                val outLines = stdout.await().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
                                val errLines = stderr.await().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
                                assertEquals((1..2000).map { "$out-$it" }, outLines)
                                assertEquals((1..2000).map { "$err-$it" }, errLines)
                            } finally {
                                child.closeAndJoin()
                            }
                        }
                    }.awaitAll()
                }
            }
        } finally {
            client.close()
        }
    }

    test("a silent cmd exit produces EOF, while cancellation of a pending read is not EOF") {
        val client = CoroutineScope(currentCoroutineContext()).ProcessClient()
        try {
            val silent = client.start(windowsCmd("exit /b 17"))
            try {
                withTimeout(5.seconds) {
                    assertEquals(17, silent.exitCode.await())
                    assertEquals(-1L, silent.stdout.readAtMostTo(Buffer(), 1))
                    assertEquals(-1L, silent.stderr.readAtMostTo(Buffer(), 1))
                }
            } finally {
                silent.closeAndJoin()
            }
            val running = client.start(delayedProcessCommand)
            try {
                coroutineScope {
                    val read = launch(start = CoroutineStart.UNDISPATCHED) {
                        running.stdout.readAtMostTo(Buffer(), 1)
                        error("Silent child completed a live read before cancellation")
                    }
                    assertFalse(read.isCompleted)
                    withTimeout(5.seconds) { read.cancelAndJoin() }
                    val next = async(start = CoroutineStart.UNDISPATCHED) {
                        assertFailsWith<IOException> { running.stdout.readAtMostTo(Buffer(), 1) }
                    }
                    assertFalse(next.isCompleted, "Read cancellation must not close the pipe")
                    running.closeAndJoin()
                    withTimeout(5.seconds) { next.await() }
                }
            } finally {
                running.closeAndJoin()
            }
        } finally {
            client.close()
        }
    }

    test("owner join drains pending read and write and keeps the terminated exit code") {
        val owner = Job()
        val client = CoroutineScope(currentCoroutineContext() + owner).ProcessClient()
        try {
            val child = client.start(delayedProcessCommand)
            coroutineScope {
                val input = Buffer().apply { write(ByteArray(512 * 1024) { 1 }) }
                val write = async(start = CoroutineStart.UNDISPATCHED) {
                    assertFailsWith<IOException> { child.stdin.write(input, input.size) }
                }
                val read = async(start = CoroutineStart.UNDISPATCHED) {
                    assertFailsWith<IOException> { child.stderr.readAtMostTo(Buffer(), 1) }
                }
                assertFalse(write.isCompleted)
                assertFalse(read.isCompleted)
                withTimeout(10.seconds) {
                    owner.cancelAndJoin()
                    read.await()
                    write.await()
                    assertTrue(child.exitCode.await() != 0)
                    assertTrue(client.coroutineContext[Job]!!.children.none())
                }
                val cleanupReturned = CompletableDeferred<Unit>()
                val caller = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        child.closeAndJoin()
                        child.closeAndJoin()
                        cleanupReturned.complete(Unit)
                    }
                }
                withTimeout(5.seconds) {
                    caller.cancelAndJoin()
                    cleanupReturned.await()
                }
            }
        } finally {
            client.close()
            withContext(NonCancellable) { owner.cancelAndJoin() }
        }
    }
}

private fun windowsCmd(command: String): ProcessCommand =
    ProcessCommand("cmd.exe", listOf("/D", "/S", "/C", command))
