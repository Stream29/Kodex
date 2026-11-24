@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    io.github.stream29.kodex.utils.processclient.NativeProcessAdapterApi::class,
)

package io.github.stream29.kodex.utils.processclient

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.processclient.cinterop.kodex_process_create_pipe
import io.github.stream29.kodex.utils.processclient.cinterop.kodex_spawn_process
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import platform.posix.EBADF
import platform.posix.ECHILD
import platform.posix.FD_CLOEXEC
import platform.posix.F_DUPFD
import platform.posix.F_GETFD
import platform.posix.F_GETFL
import platform.posix.O_NONBLOCK
import platform.posix.O_RDWR
import platform.posix.WNOHANG
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.open
import platform.posix.waitpid
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val posixProcessReactorTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("root exit is observable while a descendant holds stdout open; closing stdin permits EOF") {
        val client = CoroutineScope(currentCoroutineContext()).ProcessClient()
        try {
            // The saved fd bypasses the asynchronous subshell's default /dev/null
            // stdin. It keeps stdout open until the test closes its input.
            val process = client.start(ProcessCommand(
                "/bin/sh",
                listOf("-c", "exec 3<&0; (IFS= read -r line <&3 || :) & printf tail; exit 23"),
            ))
            try {
                withTimeout(5.seconds) {
                    assertEquals(23, process.exitCode.await())
                    process.stdin.close()
                    val output = Buffer()
                    while (process.stdout.readAtMostTo(output, 8_192) >= 0L) {
                        // A short read is allowed. Drain through EOF.
                    }
                    assertEquals("tail", output.readByteArray().decodeToString())
                    assertEquals(-1L, process.stdout.readAtMostTo(output, 1))
                }
            } finally {
                process.closeAndJoin()
            }
        } finally {
            client.close()
        }
    }

    test("cancelled read detaches; close in a cancelled job retires fd before the number can be reused") {
        withTestPipe { firstRead, _ ->
            withTestPipe { secondRead, secondWrite ->
                val fd = fcntl(firstRead, F_DUPFD, 128)
                assertTrue(fd >= 128)
                val old = adoptOwnedTestDescriptor(fd)
                try {
                    assertTrue(fcntl(fd, F_GETFD) and FD_CLOEXEC != 0)
                    assertTrue(fcntl(fd, F_GETFL) and O_NONBLOCK != 0)
                    assertFailsWith<IOException> { adoptNativeProcessStream(fd.toLong(), terminal = false) }
                    coroutineScope {
                        val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
                            old.readAtMostTo(Buffer(), 1)
                        }
                        cancelled.cancelAndJoin()
                        val reader = async(start = CoroutineStart.UNDISPATCHED) {
                            runCatching { old.readAtMostTo(Buffer(), 1) }
                        }
                        val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                awaitCancellation()
                            } finally {
                                old.close()
                            }
                        }
                        withTimeout(5.seconds) {
                            closer.cancelAndJoin()
                            val result = fcntl(fd, F_GETFD)
                            val error = errno
                            assertEquals(-1, result)
                            assertEquals(EBADF, error)
                            assertIs<IOException>(reader.await().exceptionOrNull())
                        }
                    }
                    val reused = fcntl(secondRead, F_DUPFD, fd)
                    assertTrue(reused >= 0)
                    val new = adoptOwnedTestDescriptor(reused)
                    try {
                        assertEquals(fd, reused)
                        // An old token cannot close the new endpoint, despite the same fd.
                        old.requestClose()
                        old.close()
                        val writer = adoptOwnedTestDescriptor(fcntl(secondWrite, F_DUPFD, 0))
                        try {
                            writer.write(Buffer().apply { write(byteArrayOf(42)) }, 1)
                            val sink = Buffer()
                            assertEquals(1L, withTimeout(5.seconds) { new.readAtMostTo(sink, 1) })
                            assertEquals(42, sink.readByteArray().single().toInt())
                        } finally {
                            writer.close()
                        }
                    } finally {
                        new.close()
                    }
                } finally {
                    old.close()
                }
            }
        }
    }

    test("finishReadsWithoutWaiting wakes a reader while its writer stays open") {
        withTestPipe { fd, _ ->
            val stream = adoptOwnedTestDescriptor(fcntl(fd, F_DUPFD, 0))
            try {
                coroutineScope {
                    val reader = async(start = CoroutineStart.UNDISPATCHED) {
                        stream.readAtMostTo(Buffer(), 1)
                    }
                    assertFalse(reader.isCompleted)
                    (stream as NativeReadyProcessStream).finishReadsWithoutWaiting()
                    assertEquals(-1L, withTimeout(5.seconds) { reader.await() })
                }
            } finally {
                stream.close()
            }
        }
    }

    test("exit close does not abandon a live child; terminate and close leave no waitpid status behind") {
        withTestPipe { stdinRead, stdinWrite ->
            val stdin = adoptOwnedTestDescriptor(fcntl(stdinWrite, F_DUPFD, 0))
            try {
                val pid = spawnWaitingCat(stdinRead, stdinWrite)
                val exit = watchNativeProcessExit(pid.toLong(), 0L)
                try {
                    coroutineScope {
                        val closeWait = launch(start = CoroutineStart.UNDISPATCHED) { exit.close() }
                        assertFalse(closeWait.isCompleted)
                        exit.terminate()
                        withTimeout(5.seconds) {
                            assertTrue(exit.await() != 0)
                            closeWait.join()
                        }
                    }
                    memScoped {
                        val status = alloc<IntVar>()
                        val wait = waitpid(pid, status.ptr, WNOHANG)
                        val error = errno
                        assertEquals(-1, wait)
                        assertEquals(ECHILD, error)
                    }
                } finally {
                    exit.terminate()
                    exit.close()
                }
            } finally {
                stdin.close()
            }
        }
    }

    test("owner join closes a pipe even with a read pending in a different scope") {
        val client = CoroutineScope(currentCoroutineContext()).ProcessClient()
        val process = client.start(ProcessCommand("/bin/cat"))
        try {
            coroutineScope {
                val reader = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { process.stdout.readAtMostTo(Buffer(), 1) }
                }
                client.close()
                withTimeout(5.seconds) {
                    requireNotNull(client.coroutineContext[Job]).join()
                    assertTrue(process.exitCode.isCompleted)
                    // Closing stdin and killing can also race with a legal kernel EOF.
                    val result = reader.await()
                    if (result.isSuccess) assertEquals(-1L, result.getOrThrow())
                    else assertIs<IOException>(result.exceptionOrNull())
                }
            }
        } finally {
            client.close()
            process.closeAndJoin()
        }
    }
}

private suspend fun withTestPipe(block: suspend (Int, Int) -> Unit) {
    val (read, write) = memScoped {
        val descriptors = allocArray<IntVar>(2)
        assertEquals(0, kodex_process_create_pipe(descriptors))
        descriptors[0] to descriptors[1]
    }
    try {
        block(read, write)
    } finally {
        close(read)
        close(write)
    }
}

private fun adoptOwnedTestDescriptor(fd: Int): NativeProcessStream {
    assertTrue(fd >= 0)
    return try {
        adoptNativeProcessStream(fd.toLong(), terminal = false)
    } catch (failure: Throwable) {
        close(fd)
        throw failure
    }
}

private fun spawnWaitingCat(stdinRead: Int, stdinWrite: Int): Int = memScoped {
    val nullFd = open("/dev/null", O_RDWR)
    assertTrue(nullFd >= 0)
    try {
        val pid = alloc<IntVar>()
        val group = alloc<IntVar>()
        assertEquals(
            0,
            kodex_spawn_process(
                pid = pid.ptr,
                process_group = group.ptr,
                stdin_read = stdinRead,
                stdin_write = stdinWrite,
                output_read = nullFd,
                output_write = nullFd,
                error_read = nullFd,
                error_write = nullFd,
                executable = "/bin/cat",
                arguments = null,
                argument_count = 0uL,
                environment_overrides = null,
                environment_override_count = 0uL,
                working_directory = ".",
            ),
        )
        pid.value
    } finally {
        close(nullFd)
    }
}
