package io.github.stream29.kodex.utils.shellclient

import de.infix.testBalloon.framework.core.testSuite
import de.infix.testBalloon.framework.core.TestCompartment

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private suspend fun testShellClient(): ShellClient =
    CoroutineScope(currentCoroutineContext()).ShellClient()

private suspend fun ShellClient.startTestSession(
    command: TestShellCommand,
    tty: Boolean = false,
    environment: Map<String, String> = emptyMap(),
): ProcessSession =
    start(
        ShellProcessCommand(
            command = command.command,
            shell = command.shell,
            tty = tty,
            environment = environment,
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
    repeat(100) {
        output.append(stdout.read(100.milliseconds).decodeOutput())
        if (exitCode.isCompleted) {
            return CompletedProcessOutput(output.toString(), exitCode.await())
        }
    }
    fail("Process did not finish within ten seconds.")
}

private suspend fun ProcessSession.readUntilContains(expected: String): String {
    val output = StringBuilder()
    repeat(100) {
        output.append(stdout.read(100.milliseconds).decodeOutput())
        if (expected in output) return output.toString()
    }
    fail("Process output did not contain $expected: $output")
}

val processSessionTest by testSuite(
    compartment = { TestCompartment.RealTime },
) {
    test("serializes a model shell as its executable path") {
        val parsed = Json.decodeFromString<Shell>("\"/opt/codex/bash\"")

        assertEquals(
            expected = Shell(type = ShellType.Bash, path = Path("/opt/codex/bash")),
            actual = parsed,
        )
        assertEquals(
            expected = Json.encodeToString(parsed.path.toString()),
            actual = Json.encodeToString(parsed),
        )

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

    test("starts a shell command and returns its output") {
        val client = testShellClient()
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

    test("starts a command through the dynamically resolved default shell") {
        val client = testShellClient()
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

    test("preserves separate standard output and standard error streams") {
        val client = testShellClient()
        val session = client.startTestSession(separatedOutputProcessCommand)
        try {
            assertEquals(0, withTimeout(3.seconds) { session.exitCode.await() })

            val merged = session.stdout.drain().decodeOutput()
            val standardOutput = session.standardOutput.drain().decodeOutput()
            val standardError = session.standardError.drain().decodeOutput()

            assertTrue("stdout-only" in merged)
            assertTrue("stderr-only" in merged)
            assertTrue("stdout-only" in standardOutput)
            assertFalse("stderr-only" in standardOutput)
            assertTrue("stderr-only" in standardError)
            assertFalse("stdout-only" in standardError)
        } finally {
            session.closeAndAwaitCompletion()
            client.close()
        }
    }

    test("applies command environment variables to pipes and pseudoterminals") {
        val client = testShellClient()
        try {
            environmentProbeProcessCommands.forEach { command ->
                listOf(false, true).forEach { tty ->
                    val session = client.startTestSession(
                        command = command,
                        tty = tty,
                        environment = mapOf("KODEX_SHELL_TEST" to EnvironmentProbeValue),
                    )
                    try {
                        val result = session.readUntilCompleted()

                        assertEquals(0, result.exitCode)
                        assertTrue(
                            EnvironmentProbeValue in result.output,
                            "Expected environment value from ${command.shell.type}, tty=$tty; " +
                                "output=${result.output}",
                        )
                    } finally {
                        session.closeAndAwaitCompletion()
                    }
                }
            }
        } finally {
            client.close()
        }
    }

    test("attaches a tty command to a pseudoterminal") {
        val client = testShellClient()
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

    test("accepts interactive input through a pseudoterminal") {
        val client = testShellClient()
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

    test("closing a pseudoterminal session completes its lifecycle") {
        val client = testShellClient()
        val session = client.startTestSession(delayedProcessCommand, tty = true)
        try {
            session.close()
            withTimeout(10.seconds) {
                session.exitCode.await()
            }
            withTimeout(10.seconds) {
                requireNotNull(session.scope.coroutineContext[Job]).join()
            }
        } finally {
            session.closeAndAwaitCompletion()
            client.close()
        }
    }

    test("keeps standard input open for a later send") {
        val client = testShellClient()
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

    test("returns after the requested yield time for a running process") {
        val client = testShellClient()
        val session = client.startTestSession(delayedProcessCommand)
        try {
            val result = session.stdout.read(25.milliseconds)

            assertTrue(result.isEmpty)
        } finally {
            session.closeAndAwaitCompletion()
            client.close()
        }
    }

    test("cancelling a session rejects later input") {
        val client = testShellClient()
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

    test("closing a session reports the terminated process exit code") {
        val client = testShellClient()
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

    test("a completed process rejects later input") {
        val client = testShellClient()
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

    test("closing a shell client cancels its session") {
        val client = testShellClient()
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

    test("cancelling the owner scope cancels its shell client") {
        val owner = CoroutineScope(
            currentCoroutineContext() + SupervisorJob(currentCoroutineContext()[Job]),
        )
        val client = owner.ShellClient()

        assertTrue(client.isActive)
        owner.cancel()
        assertFalse(client.isActive)
    }

    test("shell client requires an owner job") {
        val owner = object : CoroutineScope {
            override val coroutineContext: CoroutineContext = EmptyCoroutineContext
        }

        assertFailsWith<IllegalArgumentException> {
            owner.ShellClient()
        }
    }

    test("cancelling a pending read preserves later process output") {
        val client = testShellClient()
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

    test("cancelling a blocked standard-input write releases the session") {
        val client = testShellClient()
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

    test("closing standard input sends EOF and rejects later input") {
        val client = testShellClient()
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

    test("parallel processes do not retain another session's standard input") {
        val client = testShellClient()
        val waiting = client.startTestSession(interactiveProcessCommand)
        val delayed = client.startTestSession(delayedProcessCommand)
        try {
            assertTrue("ready" in waiting.readUntilContains("ready"))
            assertTrue(waiting.stdin.close())

            val result = withTimeout(1.seconds) { waiting.readUntilCompleted() }

            assertEquals(0, result.exitCode)
            assertTrue("received:" in result.output)
        } finally {
            waiting.closeAndAwaitCompletion()
            delayed.closeAndAwaitCompletion()
            client.close()
        }
    }
}

private const val EnvironmentProbeValue: String =
    "literal %PATH% !bang! \$HOME ' \" ; & |"
