package io.github.stream29.codex.lite.utils.shellclient

import de.infix.testBalloon.framework.core.testSuite
import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testScope

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val realIoTestConfig: TestConfig =
    TestConfig.testScope(isEnabled = false, timeout = 10.seconds)

private suspend fun ShellClient.startTestSession(
    command: TestShellCommand,
    tty: Boolean = false,
): ProcessSession =
    start(
        ShellProcessCommand(
            command = command.command,
            shell = command.shell,
            tty = tty,
        ),
    )

private suspend fun ProcessSession.closeAndAwaitCompletion() {
    close()
    scope.coroutineContext[Job]?.join()
}

private data class CompletedProcessOutput(
    val output: String,
    val exitCode: Int,
)

private fun StdoutBufferSnapshot.decodeOutput(): String =
    renderedBytes().decodeToString()

private suspend fun ProcessSession.readUntilCompleted(): CompletedProcessOutput {
    val output = StringBuilder()
    repeat(40) {
        output.append(stdout.read(100.milliseconds).decodeOutput())
        if (exitCode.isCompleted) {
            return CompletedProcessOutput(output.toString(), exitCode.await())
        }
    }
    fail("Process did not finish within four seconds.")
}

private suspend fun ProcessSession.readUntilContains(expected: String): String {
    val output = StringBuilder()
    repeat(40) {
        output.append(stdout.read(100.milliseconds).decodeOutput())
        if (expected in output) return output.toString()
    }
    fail("Process output did not contain $expected: $output")
}

val processSessionTest by testSuite(
    compartment = { TestCompartment.Concurrent },
) {
    test("serializes a model shell as its executable path") {
        val parsed = Json.decodeFromString<Shell>("\"/opt/codex/bash\"")

        assertEquals(
            expected = Shell(type = ShellType.Bash, path = Path("/opt/codex/bash")),
            actual = parsed,
        )
        assertEquals("\"/opt/codex/bash\"", Json.encodeToString(parsed))

        val windowsPath = Json.decodeFromString<Shell>(
            "\"C:\\\\Tools\\\\PowerShell\\\\pwsh.exe\"",
        )
        assertEquals(
            expected = Shell(type = ShellType.PowerShell, path = Path("C:\\Tools\\PowerShell\\pwsh.exe")),
            actual = windowsPath,
        )
        assertFailsWith<SerializationException> {
            Json.decodeFromString<Shell>("\"unsupported-shell\"")
        }

        val command = ShellProcessCommand(command = "echo test")

        assertEquals(Path("."), command.workingDirectory)
        assertEquals(Shell.default, command.shell)
        assertFalse(command.tty)
    }

    test("starts a shell command and returns its output", testConfig = realIoTestConfig) {
        val client = ShellClient()
        val session = client.startTestSession(oneShotProcessCommand)
        try {
            val result = session.readUntilCompleted()

            assertEquals(0, result.exitCode)
            assertTrue("one-shot" in result.output)
        } finally {
            session.closeAndAwaitCompletion()
            client.close()
        }
    }

    test("starts a command through the dynamically resolved default shell", testConfig = realIoTestConfig) {
        val client = ShellClient()
        val session = client.start(ShellProcessCommand(command = "echo default-shell"))
        try {
            val result = session.readUntilCompleted()

            assertEquals(0, result.exitCode)
            assertTrue("default-shell" in result.output)
        } finally {
            session.closeAndAwaitCompletion()
            client.close()
        }
    }

    test("attaches a tty command to a pseudoterminal", testConfig = realIoTestConfig) {
        val client = ShellClient()
        val session = client.startTestSession(ttyProbeProcessCommand, tty = true)
        try {
            val result = session.readUntilCompleted()

            assertEquals(
                expected = 0,
                actual = result.exitCode,
                message = "Expected a PTY probe to exit normally, but got output: ${result.output}",
            )
            assertTrue(
                actual = "tty=yes" in result.output,
                message = "Expected a PTY probe result of tty=yes, but got: ${result.output}",
            )
        } finally {
            session.closeAndAwaitCompletion()
            client.close()
        }
    }

    test("accepts interactive input through a pseudoterminal", testConfig = realIoTestConfig) {
        val client = ShellClient()
        val session = client.startTestSession(interactiveProcessCommand, tty = true)
        try {
            assertTrue("ready" in session.readUntilContains("ready"))
            session.stdin.send("hello from tty\n")
            val result = session.readUntilCompleted()

            assertEquals(
                expected = 0,
                actual = result.exitCode,
                message = "Expected the PTY interaction to exit normally, but got output: ${result.output}",
            )
            assertTrue(
                actual = "received:hello from tty" in result.output,
                message = "Expected the PTY to echo its input, but got: ${result.output}",
            )
        } finally {
            session.closeAndAwaitCompletion()
            client.close()
        }
    }

    test("closing a pseudoterminal session completes its lifecycle", testConfig = realIoTestConfig) {
        val client = ShellClient()
        val session = client.startTestSession(delayedProcessCommand, tty = true)
        try {
            session.close()
            withTimeout(3.seconds) {
                session.exitCode.await()
            }
            withTimeout(3.seconds) {
                requireNotNull(session.scope.coroutineContext[Job]).join()
            }
        } finally {
            session.closeAndAwaitCompletion()
            client.close()
        }
    }

    test("keeps standard input open for a later send", testConfig = realIoTestConfig) {
        val client = ShellClient()
        val session = client.startTestSession(interactiveProcessCommand)
        try {
            assertTrue("ready" in session.readUntilContains("ready"))
            session.stdin.send("hello from stdin\n")
            val result = session.readUntilCompleted()

            assertEquals(
                expected = 0,
                actual = result.exitCode,
                message = "Expected standard-input interaction to exit normally, but got output: ${result.output}",
            )
            assertTrue(
                actual = "received:hello from stdin" in result.output,
                message = "Expected standard input to be echoed, but got: ${result.output}",
            )
        } finally {
            session.closeAndAwaitCompletion()
            client.close()
        }
    }

    test("returns after the requested yield time for a running process", testConfig = realIoTestConfig) {
        val client = ShellClient()
        val session = client.startTestSession(delayedProcessCommand)
        try {
            val result = session.stdout.read(25.milliseconds)

            assertTrue(result.isEmpty)
        } finally {
            session.closeAndAwaitCompletion()
            client.close()
        }
    }

    test("cancelling a session rejects later input", testConfig = realIoTestConfig) {
        val client = ShellClient()
        val session = client.startTestSession(delayedProcessCommand)
        try {
            session.scope.cancel()
            try {
                session.stdin.send("late input\n")
                fail("Cancelled process session accepted input.")
            } catch (_: CancellationException) {
                // Expected: a cancelled session cannot accept input.
            }
        } finally {
            session.closeAndAwaitCompletion()
            client.close()
        }
    }

    test("closing a session reports the terminated process exit code", testConfig = realIoTestConfig) {
        val client = ShellClient()
        val session = client.startTestSession(delayedProcessCommand)
        try {
            session.close()
            val terminatedExitCode = withTimeout(3.seconds) {
                session.exitCode.await()
            }
            assertTrue(terminatedExitCode != 0)
            withTimeout(3.seconds) {
                requireNotNull(session.scope.coroutineContext[Job]).join()
            }
        } finally {
            session.closeAndAwaitCompletion()
            client.close()
        }
    }

    test("a completed process rejects later input", testConfig = realIoTestConfig) {
        val client = ShellClient()
        val session = client.startTestSession(oneShotProcessCommand)
        try {
            assertEquals(0, session.readUntilCompleted().exitCode)
            assertFailsWith<ClosedSendChannelException> {
                session.stdin.send("late input\n")
            }
        } finally {
            session.closeAndAwaitCompletion()
            client.close()
        }
    }

    test("closing a shell client cancels its session", testConfig = realIoTestConfig) {
        val client = ShellClient()
        val session = client.startTestSession(delayedProcessCommand)
        try {
            client.close()
            try {
                session.stdout.read(0.milliseconds)
                fail("Closed shell client left its process session usable.")
            } catch (_: CancellationException) {
                // Expected: session ownership follows the shell client.
            }
        } finally {
            session.closeAndAwaitCompletion()
            client.close()
        }
    }

    test(
        "cancelling a pending read preserves later process output",
        testConfig = realIoTestConfig,
    ) {
        val client = ShellClient()
        val session = client.startTestSession(interactiveProcessCommand)
        try {
            assertTrue("ready" in session.readUntilContains("ready"))
            coroutineScope {
                val read = async(start = CoroutineStart.UNDISPATCHED) {
                    session.stdout.read(30.seconds)
                }
                delay(100.milliseconds)
                read.cancel()
                try {
                    read.await()
                    fail("Canceled process read completed normally.")
                } catch (_: CancellationException) {
                    // Expected: every platform implementation must cooperate with coroutine cancellation.
                }
            }
            session.stdin.send("after cancellation\n")
            val result = session.readUntilCompleted()

            assertEquals(0, result.exitCode)
            assertTrue("received:after cancellation" in result.output)
        } finally {
            session.closeAndAwaitCompletion()
            client.close()
        }
    }

    test("cancelling a blocked standard-input write releases the session", testConfig = realIoTestConfig) {
        val client = ShellClient()
        val session = client.startTestSession(delayedProcessCommand)
        try {
            coroutineScope {
                val sent = async(start = CoroutineStart.UNDISPATCHED) {
                    session.stdin.send("x".repeat(1_048_576))
                }
                delay(100.milliseconds)
                assertFalse(sent.isCompleted)

                session.scope.cancel()
                withTimeout(3.seconds) {
                    requireNotNull(session.scope.coroutineContext[Job]).join()
                }
                assertFailsWith<CancellationException> {
                    sent.await()
                }
            }
        } finally {
            session.closeAndAwaitCompletion()
            client.close()
        }
    }

    test("closing standard input sends EOF and rejects later input", testConfig = realIoTestConfig) {
        val client = ShellClient()
        val session = client.startTestSession(interactiveProcessCommand)
        try {
            assertTrue("ready" in session.readUntilContains("ready"))
            assertTrue(session.stdin.close())
            assertFailsWith<ClosedSendChannelException> {
                session.stdin.send("late input\n")
            }
            val result = session.readUntilCompleted()

            assertEquals(0, result.exitCode)
            assertTrue("received:" in result.output)
        } finally {
            session.closeAndAwaitCompletion()
            client.close()
        }
    }
}
