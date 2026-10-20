@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.tool.unifiedexec

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionToolEvent
import io.github.stream29.kodex.openai.ResponsesApiTool
import io.github.stream29.kodex.tool.contract.Tool
import io.github.stream29.kodex.utils.shellclient.ProcessException
import io.github.stream29.kodex.utils.shellclient.ProcessSession
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellClient
import io.github.stream29.kodex.utils.shellclient.ShellProcessCommand
import io.github.stream29.kodex.utils.shellclient.ShellSettings
import io.github.stream29.kodex.utils.shellclient.ShellType
import io.github.stream29.kodex.utils.shellclient.StdoutBuffer
import io.github.stream29.kodex.utils.shellclient.StdoutBufferSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val TenMinuteYieldWindowMillis: Long = 600_000L
private const val TenMinuteBoundaryPendingMillis: Long = 599_999L
private val RealTimeSmokeTimeout: Duration = 30.seconds
private val WaitCleanupTimeout: Duration = 5.seconds

private suspend fun withWaitCleanup(
    cleanup: suspend () -> Unit,
    block: suspend () -> Unit,
) {
    var originalFailure: Throwable? = null
    try {
        block()
    } catch (failure: Throwable) {
        originalFailure = failure
        throw failure
    } finally {
        try {
            withContext(NonCancellable) {
                withTimeout(WaitCleanupTimeout) { cleanup() }
            }
        } catch (cleanupFailure: Throwable) {
            val original = originalFailure
            if (original == null) throw cleanupFailure
            original.addSuppressed(cleanupFailure)
        }
    }
}

private data class WaitRegressionShellSettings(
    override val shell: Shell = unifiedExecTestShell,
) : ShellSettings

private val EmptyControllableStdoutSnapshot: StdoutBufferSnapshot =
    StdoutBufferSnapshot(head = ByteArray(0), tail = ByteArray(0), omittedByteCount = 0L)

private class ControllableStdoutBuffer : StdoutBuffer {
    private val builder: StringBuilder = StringBuilder()

    fun append(text: String) {
        builder.append(text)
    }

    override suspend fun drain(): StdoutBufferSnapshot {
        if (builder.isEmpty()) return EmptyControllableStdoutSnapshot
        val bytes = builder.toString().encodeToByteArray()
        builder.clear()
        return StdoutBufferSnapshot(
            head = bytes,
            tail = ByteArray(0),
            omittedByteCount = 0L,
        )
    }

    override suspend fun read(yieldTime: Duration): StdoutBufferSnapshot = drain()
}

private class ControllableProcessSession(
    val command: ShellProcessCommand,
    parentScope: CoroutineScope,
) : ProcessSession {
    private val sessionJob: CompletableJob = SupervisorJob(parentScope.coroutineContext[Job])
    override val scope: CoroutineScope = CoroutineScope(parentScope.coroutineContext + sessionJob)

    private val stdinChannel: Channel<String> = Channel(Channel.UNLIMITED)
    private val stdoutBuffer: ControllableStdoutBuffer = ControllableStdoutBuffer()
    private val stderrBuffer: ControllableStdoutBuffer = ControllableStdoutBuffer()
    private val exitCodeDeferred: CompletableDeferred<Int> = CompletableDeferred()

    override val stdin: SendChannel<String>
        get() = stdinChannel
    override val stdout: StdoutBuffer
        get() = stdoutBuffer
    override val standardOutput: StdoutBuffer
        get() = stdoutBuffer
    override val standardError: StdoutBuffer
        get() = stderrBuffer
    override val exitCode: Deferred<Int>
        get() = exitCodeDeferred

    var closeCount: Int = 0
        private set

    val isClosed: Boolean
        get() = closeCount > 0

    fun appendStdout(text: String) {
        stdoutBuffer.append(text)
    }

    fun drainReceivedStdin(): List<String> = buildList {
        while (true) {
            val received = stdinChannel.tryReceive().getOrNull() ?: break
            add(received)
        }
    }

    fun completeExit(code: Int) {
        stdinChannel.close()
        exitCodeDeferred.complete(code)
    }

    override fun close() {
        closeCount += 1
        stdinChannel.close()
        sessionJob.cancel()
    }

    suspend fun joinScope() {
        sessionJob.join()
    }
}

private class ControllableShellClient(
    parentScope: CoroutineScope,
) : ShellClient {
    private val clientJob: CompletableJob = SupervisorJob(parentScope.coroutineContext[Job])
    override val coroutineContext: CoroutineContext = parentScope.coroutineContext + clientJob

    private val createdSessions: MutableList<ControllableProcessSession> = mutableListOf()

    val sessions: List<ControllableProcessSession>
        get() = createdSessions.toList()

    var closeCount: Int = 0
        private set

    override suspend fun start(command: ShellProcessCommand): ProcessSession {
        if (!isActive) throw ProcessException("Shell client is closed.")
        return ControllableProcessSession(
            command = command,
            parentScope = this,
        ).also { session ->
            createdSessions += session
        }
    }

    override fun close() {
        closeCount += 1
        for (session in createdSessions) {
            session.close()
        }
        clientJob.cancel()
    }

    suspend fun closeAndJoin() {
        close()
        for (session in createdSessions) {
            session.joinScope()
        }
        clientJob.cancelAndJoin()
    }
}

private class VirtualWaitHarness(
    val testScope: TestScope,
    val shellClient: ControllableShellClient,
    val client: UnifiedExecToolClient,
) {
    val tools: List<Tool> = UnifiedExecTools.createTools(client)
    val execTool: Tool = tools.single { tool -> (tool.spec as ResponsesApiTool).name == UnifiedExecTools.ExecCommandName }
    val writeTool: Tool = tools.single { tool -> (tool.spec as ResponsesApiTool).name == UnifiedExecTools.WriteStdinName }

    suspend fun startYieldedSession(
        command: String = "interactive-lock-holder",
        initialStdout: String = "",
    ): Pair<Int, ControllableProcessSession> {
        val initialYieldMillis = normalizedExecYieldTimeMillis(
            requestedMillis = UnifiedExecMinimumYieldTimeMillis,
            platform = execCommandHostPlatform,
        )
        val execDeferred = testScope.async(start = CoroutineStart.UNDISPATCHED) {
            client.execCommand(
                ExecCommandArguments(
                    command = command,
                    yieldTimeMillis = initialYieldMillis,
                ),
            )
        }
        testScope.runCurrent()
        val session = shellClient.sessions.last()
        if (initialStdout.isNotEmpty()) {
            session.appendStdout(initialStdout)
        }
        testScope.advanceTimeBy(initialYieldMillis)
        testScope.runCurrent()
        val initialOutput = execDeferred.await()
        val sessionId = assertNotNull(initialOutput.sessionId)
        assertNull(initialOutput.exitCode)
        assertFalse(session.isClosed)
        assertTrue(session.scope.isActive)
        return sessionId to session
    }
}

private suspend fun TestScope.withVirtualWaitHarness(
    block: suspend VirtualWaitHarness.() -> Unit,
) {
    val shellClient = ControllableShellClient(this)
    val client = UnifiedExecToolClient(
        workingDirectoryProvider = { Path(".") },
        settingsProvider = { WaitRegressionShellSettings() },
        shellClient = shellClient,
    )
    val harness = VirtualWaitHarness(
        testScope = this,
        shellClient = shellClient,
        client = client,
    )
    withWaitCleanup(
        cleanup = {
            client.close()
            shellClient.closeAndJoin()
        },
    ) {
        harness.block()
    }
}

private suspend fun Tool.invokeExec(arguments: ExecCommandArguments): UnifiedExecOutput {
    val event = assertIs<StableCommandExecutionToolEvent>(
        handle(
            PendingCommandExecutionToolEvent(
                callId = "call_exec_wait_regression",
                action = PendingCommandExecutionAction.ExecCommand(arguments),
            ),
        ),
    )
    return when (val completed = event.result) {
        is StableCommandExecutionResult.Output -> completed.value
        is StableCommandExecutionResult.Failure -> fail("exec_command tool failed: ${completed.message}")
    }
}

private suspend fun Tool.invokeWrite(arguments: WriteStdinArguments): UnifiedExecOutput {
    val event = assertIs<StableCommandExecutionToolEvent>(
        handle(
            PendingCommandExecutionToolEvent(
                callId = "call_write_wait_regression",
                action = PendingCommandExecutionAction.WriteStdin(arguments),
            ),
        ),
    )
    return when (val completed = event.result) {
        is StableCommandExecutionResult.Output -> completed.value
        is StableCommandExecutionResult.Failure -> fail("write_stdin tool failed: ${completed.message}")
    }
}

private suspend fun realTimeUnifiedExecToolClient(
    workingDirectory: Path = Path("."),
): UnifiedExecToolClient =
    CoroutineScope(currentCoroutineContext()).UnifiedExecToolClient(
        settingsProvider = { WaitRegressionShellSettings() },
        workingDirectoryProvider = { workingDirectory },
    )

private suspend fun UnifiedExecToolClient.closeAndAwaitSessions() {
    val sessionJobs = activeSessions.value.values.mapNotNull { session ->
        (session as? ManagedProcessSession)?.session?.scope?.coroutineContext?.get(Job)
    }
    close()
    for (job in sessionJobs) {
        job.join()
    }
}

private val realTimePhasedOutputCommand: String
    get() = if (unifiedExecTestShell.type == ShellType.PowerShell) {
        "Write-Output burst-one; Start-Sleep -Milliseconds 400; Write-Output burst-two"
    } else {
        "printf 'burst-one\\n'; sleep 0.4; printf 'burst-two\\n'"
    }

private val realTimeSlowAfterStdinCommand: String
    get() = if (unifiedExecTestShell.type == ShellType.PowerShell) {
        "Write-Output ready; \$line = [Console]::In.ReadLine(); Write-Output \"ack:\$line\"; Start-Sleep -Seconds 1; Write-Output after-sleep"
    } else {
        "printf 'ready\\n'; IFS= read -r line; printf 'ack:%s\\n' \"\$line\"; sleep 1; printf 'after-sleep\\n'"
    }

private fun createPrivateTempDirectory(prefix: String): Path {
    val directory = Path(
        SystemTemporaryDirectory,
        "$prefix-${Random.nextLong().toULong().toString(16)}",
    )
    SystemFileSystem.createDirectories(directory, mustCreate = true)
    return directory
}

private fun deleteDirectoryRecursively(path: Path) {
    val metadata = SystemFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        for (child in SystemFileSystem.list(path)) {
            deleteDirectoryRecursively(child)
        }
    }
    SystemFileSystem.delete(path, mustExist = false)
}

private suspend fun requireLinuxFlockAvailable(client: UnifiedExecToolClient) {
    val probe = client.execCommand(
        ExecCommandArguments(
            command = "command -v flock",
            shell = unifiedExecTestShell,
            yieldTimeMillis = 5_000L,
        ),
    )
    if (probe.exitCode != 0 || probe.output.isBlank()) {
        fail(
            "UNVERIFIED: Linux flock CLI is unavailable on this host " +
                "(exitCode=${probe.exitCode}, output=${probe.output.trim()}); " +
                "cannot verify private tempfile flock wait behavior.",
        )
    }
}

private suspend fun awaitFileExists(path: Path) {
    while (!SystemFileSystem.exists(path)) {
        delay(20.milliseconds)
    }
}

val unifiedExecVirtualWaitRegressionTest by testSuite(
    testConfig = TestConfig.testScope(isEnabled = false),
) {
    test("exec_command stays pending at 599999 ms and yields an alive session at 600000 ms") {
        runTest {
            withVirtualWaitHarness {
                val execDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                    execTool.invokeExec(
                        ExecCommandArguments(
                            command = "flock /tmp/private.lock ./build.sh",
                            yieldTimeMillis = TenMinuteYieldWindowMillis,
                        ),
                    )
                }
                runCurrent()
                val session = shellClient.sessions.single()
                session.appendStdout("waiting-for-lock\n")
                assertEquals(1, client.activeSessions.value.size)

                advanceTimeBy(TenMinuteBoundaryPendingMillis)
                runCurrent()
                assertFalse(execDeferred.isCompleted, "exec_command must still be suspended at 599999 ms.")
                assertFalse(session.isClosed)
                assertTrue(session.scope.isActive)

                advanceTimeBy(1L)
                runCurrent()
                assertTrue(execDeferred.isCompleted, "exec_command must yield at 600000 ms.")
                val output = execDeferred.await()
                val sessionId = assertNotNull(output.sessionId)
                assertNull(output.exitCode)
                assertEquals("waiting-for-lock\n", output.output)
                assertFalse(session.isClosed, "Ordinary yield must not close the underlying process session.")
                assertTrue(session.scope.isActive)
                assertTrue(sessionId in client.activeSessions.value)
            }
        }
    }

    test("empty write_stdin poll stays pending at 599999 ms and yields an alive session at 600000 ms") {
        runTest {
            withVirtualWaitHarness {
                val (sessionId, session) = startYieldedSession(
                    command = "long-running-task",
                    initialStdout = "booted\n",
                )
                session.appendStdout("still-working\n")

                val pollDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                    writeTool.invokeWrite(
                        WriteStdinArguments(
                            sessionId = sessionId,
                            chars = "",
                            yieldTimeMillis = TenMinuteYieldWindowMillis,
                        ),
                    )
                }
                runCurrent()
                assertTrue(session.drainReceivedStdin().isEmpty())

                advanceTimeBy(TenMinuteBoundaryPendingMillis)
                runCurrent()
                assertFalse(pollDeferred.isCompleted, "Empty write_stdin poll must stay suspended at 599999 ms.")
                assertFalse(session.isClosed)

                advanceTimeBy(1L)
                runCurrent()
                assertTrue(pollDeferred.isCompleted, "Empty write_stdin poll must yield at 600000 ms.")
                val output = pollDeferred.await()
                assertNull(output.exitCode)
                assertEquals(sessionId, output.sessionId)
                assertEquals("still-working\n", output.output)
                assertFalse(session.isClosed, "Ordinary empty-poll yield must keep the session open.")
                assertTrue(session.scope.isActive)
                assertTrue(sessionId in client.activeSessions.value)
            }
        }
    }

    test("nonempty write_stdin stays pending at 599999 ms and yields an alive session at 600000 ms") {
        runTest {
            withVirtualWaitHarness {
                val (sessionId, session) = startYieldedSession(
                    command = "interactive-long-step",
                )

                val writeDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                    writeTool.invokeWrite(
                        WriteStdinArguments(
                            sessionId = sessionId,
                            chars = "continue-after-lock\n",
                            yieldTimeMillis = TenMinuteYieldWindowMillis,
                        ),
                    )
                }
                runCurrent()
                assertEquals(listOf("continue-after-lock\n"), session.drainReceivedStdin())
                session.appendStdout("step-started\n")

                advanceTimeBy(TenMinuteBoundaryPendingMillis)
                runCurrent()
                assertFalse(writeDeferred.isCompleted, "Nonempty write_stdin must stay suspended at 599999 ms.")
                assertFalse(session.isClosed)

                advanceTimeBy(1L)
                runCurrent()
                assertTrue(writeDeferred.isCompleted, "Nonempty write_stdin must yield at 600000 ms.")
                val output = writeDeferred.await()
                assertNull(output.exitCode)
                assertEquals(sessionId, output.sessionId)
                assertEquals("step-started\n", output.output)
                assertFalse(session.isClosed, "Ordinary nonempty-write yield must keep the session open.")
                assertTrue(session.scope.isActive)
                assertTrue(sessionId in client.activeSessions.value)
            }
        }
    }

    test("requests above 600000 ms clamp to the 600000 ms ceiling for exec_command and write_stdin") {
        runTest {
            withVirtualWaitHarness {
                val execDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                    client.execCommand(
                        ExecCommandArguments(
                            command = "over-cap-command",
                            yieldTimeMillis = 1_200_000L,
                        ),
                    )
                }
                runCurrent()
                advanceTimeBy(TenMinuteBoundaryPendingMillis)
                runCurrent()
                assertFalse(execDeferred.isCompleted)

                advanceTimeBy(1L)
                runCurrent()
                assertTrue(execDeferred.isCompleted)
                val sessionId = assertNotNull(execDeferred.await().sessionId)

                val emptyPollDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                    client.writeStdin(
                        WriteStdinArguments(
                            sessionId = sessionId,
                            chars = "",
                            yieldTimeMillis = 1_200_000L,
                        ),
                    )
                }
                runCurrent()
                advanceTimeBy(TenMinuteBoundaryPendingMillis)
                runCurrent()
                assertFalse(emptyPollDeferred.isCompleted)

                advanceTimeBy(1L)
                runCurrent()
                assertTrue(emptyPollDeferred.isCompleted)
                assertEquals(sessionId, emptyPollDeferred.await().sessionId)

                val nonEmptyWriteDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                    client.writeStdin(
                        WriteStdinArguments(
                            sessionId = sessionId,
                            chars = "input\n",
                            yieldTimeMillis = 1_200_000L,
                        ),
                    )
                }
                runCurrent()
                advanceTimeBy(TenMinuteBoundaryPendingMillis)
                runCurrent()
                assertFalse(nonEmptyWriteDeferred.isCompleted)

                advanceTimeBy(1L)
                runCurrent()
                assertTrue(nonEmptyWriteDeferred.isCompleted)
                assertEquals(sessionId, nonEmptyWriteDeferred.await().sessionId)
            }
        }
    }

    for (earlyExitMillis in listOf(1_000L, 40_000L, 310_000L)) {
        test("exec_command with 600000 ms window returns in one call when process exits at ${earlyExitMillis} ms") {
            runTest {
                withVirtualWaitHarness {
                    val startedAt = currentTime
                    val execDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                        execTool.invokeExec(
                            ExecCommandArguments(
                                command = "build-step-$earlyExitMillis",
                                yieldTimeMillis = TenMinuteYieldWindowMillis,
                            ),
                        )
                    }
                    runCurrent()
                    val session = shellClient.sessions.single()

                    advanceTimeBy(earlyExitMillis - 1L)
                    runCurrent()
                    assertFalse(
                        execDeferred.isCompleted,
                        "exec_command must not yield before $earlyExitMillis ms (including old 30s/300s caps).",
                    )

                    advanceTimeBy(1L)
                    session.appendStdout("done-at-$earlyExitMillis\n")
                    session.completeExit(0)
                    runCurrent()

                    assertTrue(execDeferred.isCompleted)
                    assertEquals(earlyExitMillis, currentTime - startedAt)
                    val result = execDeferred.await()
                    assertEquals(0, result.exitCode)
                    assertNull(result.sessionId)
                    assertEquals("done-at-$earlyExitMillis\n", result.output)
                    assertTrue(session.isClosed)
                    assertTrue(client.activeSessions.value.isEmpty())
                }
            }
        }

        test("empty write_stdin with 600000 ms window returns in one call when process exits at ${earlyExitMillis} ms") {
            runTest {
                withVirtualWaitHarness {
                    val (sessionId, session) = startYieldedSession(command = "poll-step-$earlyExitMillis")
                    val startedAt = currentTime
                    val pollDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                        writeTool.invokeWrite(
                            WriteStdinArguments(
                                sessionId = sessionId,
                                chars = "",
                                yieldTimeMillis = TenMinuteYieldWindowMillis,
                            ),
                        )
                    }
                    runCurrent()

                    advanceTimeBy(earlyExitMillis - 1L)
                    runCurrent()
                    assertFalse(
                        pollDeferred.isCompleted,
                        "Empty write_stdin must not yield before $earlyExitMillis ms.",
                    )

                    advanceTimeBy(1L)
                    session.appendStdout("poll-done-$earlyExitMillis\n")
                    session.completeExit(0)
                    runCurrent()

                    assertTrue(pollDeferred.isCompleted)
                    assertEquals(earlyExitMillis, currentTime - startedAt)
                    val result = pollDeferred.await()
                    assertEquals(0, result.exitCode)
                    assertNull(result.sessionId)
                    assertEquals("poll-done-$earlyExitMillis\n", result.output)
                    assertTrue(session.isClosed)
                    assertTrue(client.activeSessions.value.isEmpty())
                }
            }
        }

        test("nonempty write_stdin with 600000 ms window returns in one call when process exits at ${earlyExitMillis} ms") {
            runTest {
                withVirtualWaitHarness {
                    val (sessionId, session) = startYieldedSession(command = "stdin-step-$earlyExitMillis")
                    val startedAt = currentTime
                    val writeDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                        writeTool.invokeWrite(
                            WriteStdinArguments(
                                sessionId = sessionId,
                                chars = "go-$earlyExitMillis\n",
                                yieldTimeMillis = TenMinuteYieldWindowMillis,
                            ),
                        )
                    }
                    runCurrent()
                    assertEquals(listOf("go-$earlyExitMillis\n"), session.drainReceivedStdin())

                    advanceTimeBy(earlyExitMillis - 1L)
                    runCurrent()
                    assertFalse(
                        writeDeferred.isCompleted,
                        "Nonempty write_stdin must not yield before $earlyExitMillis ms (including old 30s/300s caps).",
                    )

                    advanceTimeBy(1L)
                    session.appendStdout("stdin-done-$earlyExitMillis\n")
                    session.completeExit(0)
                    runCurrent()

                    assertTrue(writeDeferred.isCompleted)
                    assertEquals(earlyExitMillis, currentTime - startedAt)
                    val result = writeDeferred.await()
                    assertEquals(0, result.exitCode)
                    assertNull(result.sessionId)
                    assertEquals("stdin-done-$earlyExitMillis\n", result.output)
                    assertTrue(session.isClosed)
                    assertTrue(client.activeSessions.value.isEmpty())
                }
            }
        }
    }

    test("phased stdout arrival does not end wait early and subsequent calls drain only remaining output") {
        runTest {
            withVirtualWaitHarness {
                val execDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                    client.execCommand(
                        ExecCommandArguments(
                            command = "phased-long-command",
                            yieldTimeMillis = TenMinuteYieldWindowMillis,
                        ),
                    )
                }
                runCurrent()
                val session = shellClient.sessions.single()

                advanceTimeBy(1_000L)
                session.appendStdout("chunk-1\n")
                runCurrent()
                assertFalse(execDeferred.isCompleted, "First output burst must not end exec_command wait early.")

                advanceTimeBy(40_000L)
                session.appendStdout("chunk-2\n")
                runCurrent()
                assertFalse(execDeferred.isCompleted, "Second output burst must not end exec_command wait early.")

                advanceTimeBy(TenMinuteYieldWindowMillis - 41_000L)
                runCurrent()
                assertTrue(execDeferred.isCompleted)
                val firstResult = execDeferred.await()
                val sessionId = assertNotNull(firstResult.sessionId)
                assertNull(firstResult.exitCode)
                assertEquals("chunk-1\nchunk-2\n", firstResult.output)

                val followUpDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                    client.writeStdin(
                        WriteStdinArguments(
                            sessionId = sessionId,
                            chars = "",
                            yieldTimeMillis = TenMinuteYieldWindowMillis,
                        ),
                    )
                }
                runCurrent()

                advanceTimeBy(15_000L)
                session.appendStdout("chunk-3\n")
                runCurrent()
                assertFalse(followUpDeferred.isCompleted, "Intermediate output burst must not end write_stdin wait early.")

                advanceTimeBy(25_000L)
                session.appendStdout("chunk-4\n")
                session.completeExit(42)
                runCurrent()

                assertTrue(followUpDeferred.isCompleted)
                val finalResult = followUpDeferred.await()
                assertEquals(42, finalResult.exitCode)
                assertNull(finalResult.sessionId)
                assertEquals("chunk-3\nchunk-4\n", finalResult.output)
                assertFalse("chunk-1" in finalResult.output)
                assertFalse("chunk-2" in finalResult.output)
                assertTrue(session.isClosed)
                assertTrue(client.activeSessions.value.isEmpty())
            }
        }
    }

    test("cancellation closes and unregisters the session while ordinary yield keeps it alive") {
        runTest {
            withVirtualWaitHarness {
                val (yieldedSessionId, yieldedSession) = startYieldedSession(
                    command = "survives-ordinary-yield",
                    initialStdout = "still-alive\n",
                )
                assertFalse(yieldedSession.isClosed)
                assertTrue(yieldedSession.scope.isActive)
                assertTrue(yieldedSessionId in client.activeSessions.value)

                val cancelledWrite = async(start = CoroutineStart.UNDISPATCHED) {
                    client.writeStdin(
                        WriteStdinArguments(
                            sessionId = yieldedSessionId,
                            chars = "",
                            yieldTimeMillis = TenMinuteYieldWindowMillis,
                        ),
                    )
                }
                runCurrent()
                advanceTimeBy(50_000L)
                runCurrent()
                assertFalse(cancelledWrite.isCompleted)

                cancelledWrite.cancelAndJoin()
                runCurrent()
                assertTrue(yieldedSession.isClosed, "Cancelling write_stdin must close the underlying session.")
                assertFalse(yieldedSession.scope.isActive)
                assertFalse(yieldedSessionId in client.activeSessions.value)
                assertFailsWith<UnifiedExecToolException> {
                    client.writeStdin(
                        WriteStdinArguments(
                            sessionId = yieldedSessionId,
                            chars = "",
                            yieldTimeMillis = UnifiedExecMinimumEmptyPollYieldTimeMillis,
                        ),
                    )
                }

                val cancelledExec = async(start = CoroutineStart.UNDISPATCHED) {
                    client.execCommand(
                        ExecCommandArguments(
                            command = "cancelled-exec",
                            yieldTimeMillis = TenMinuteYieldWindowMillis,
                        ),
                    )
                }
                runCurrent()
                val secondSession = shellClient.sessions.last()
                val secondSessionId = client.activeSessions.value.keys.single()
                advanceTimeBy(45_000L)
                runCurrent()
                assertFalse(cancelledExec.isCompleted)

                cancelledExec.cancelAndJoin()
                runCurrent()
                assertTrue(secondSession.isClosed, "Cancelling exec_command must close the underlying session.")
                assertFalse(secondSession.scope.isActive)
                assertTrue(client.activeSessions.value.isEmpty())
                assertFailsWith<UnifiedExecToolException> {
                    client.writeStdin(
                        WriteStdinArguments(
                            sessionId = secondSessionId,
                            chars = "",
                            yieldTimeMillis = UnifiedExecMinimumEmptyPollYieldTimeMillis,
                        ),
                    )
                }
                assertTrue(shellClient.isActive, "Cancelling individual calls must not cancel the shell client scope.")
            }
        }
    }
}

val unifiedExecRealTimeWaitSmokeTest by testSuite(
    compartment = { TestCompartment.RealTime },
    testConfig = TestConfig.testScope(isEnabled = false, timeout = RealTimeSmokeTimeout),
) {
    test("fast echo with 600000 ms yield window returns promptly on exit") {
        withTimeout(RealTimeSmokeTimeout) {
            val client = realTimeUnifiedExecToolClient()
            withWaitCleanup(cleanup = { client.closeAndAwaitSessions() }) {
                val result = client.execCommand(
                    ExecCommandArguments(
                        command = "echo kodex-ten-minute-fast-exit",
                        shell = unifiedExecTestShell,
                        yieldTimeMillis = TenMinuteYieldWindowMillis,
                    ),
                )
                assertEquals(0, result.exitCode)
                assertNull(result.sessionId)
                assertTrue("kodex-ten-minute-fast-exit" in result.output, "Output: ${result.output}")
                assertTrue(client.activeSessions.value.isEmpty())
            }
        }
    }

    test("phased pipe output waits until process exit within a long yield window") {
        withTimeout(RealTimeSmokeTimeout) {
            val client = realTimeUnifiedExecToolClient()
            withWaitCleanup(cleanup = { client.closeAndAwaitSessions() }) {
                val result = client.execCommand(
                    ExecCommandArguments(
                        command = realTimePhasedOutputCommand,
                        shell = unifiedExecTestShell,
                        yieldTimeMillis = TenMinuteYieldWindowMillis,
                    ),
                )
                assertEquals(0, result.exitCode)
                assertNull(result.sessionId)
                assertTrue("burst-one" in result.output, "Output: ${result.output}")
                assertTrue("burst-two" in result.output, "Output: ${result.output}")
                assertTrue(client.activeSessions.value.isEmpty())
            }
        }
    }

    test("short yield window expires and follow-up long poll collects remaining output without duplication") {
        withTimeout(RealTimeSmokeTimeout) {
            val client = realTimeUnifiedExecToolClient()
            withWaitCleanup(cleanup = { client.closeAndAwaitSessions() }) {
                val initial = client.execCommand(
                    ExecCommandArguments(
                        command = realTimeSlowAfterStdinCommand,
                        shell = unifiedExecTestShell,
                        yieldTimeMillis = UnifiedExecMinimumYieldTimeMillis,
                    ),
                )
                val sessionId = assertNotNull(initial.sessionId)
                assertNull(initial.exitCode)

                val partial = client.writeStdin(
                    WriteStdinArguments(
                        sessionId = sessionId,
                        chars = "resume\n",
                        yieldTimeMillis = UnifiedExecMinimumYieldTimeMillis,
                    ),
                )
                assertNull(partial.exitCode)
                assertEquals(sessionId, partial.sessionId)

                val completed = client.writeStdin(
                    WriteStdinArguments(
                        sessionId = sessionId,
                        chars = "",
                        yieldTimeMillis = TenMinuteYieldWindowMillis,
                    ),
                )
                assertEquals(0, completed.exitCode)
                assertNull(completed.sessionId)

                val combined = initial.output + partial.output + completed.output
                assertEquals(1, Regex("ack:resume").findAll(combined).count(), "Combined output: $combined")
                assertEquals(1, Regex("after-sleep").findAll(combined).count(), "Combined output: $combined")
                assertTrue(client.activeSessions.value.isEmpty())
            }
        }
    }

    if (execCommandHostPlatform == ExecCommandHostPlatform.Linux) {
        test("Linux private tempfile flock waiter stays blocked while held and completes in the same call upon release") {
            withTimeout(RealTimeSmokeTimeout) {
                val tempDir = createPrivateTempDirectory("kodex-unified-exec-flock-wait")
                val lockFile = Path(tempDir, "private.lock")
                val holderReadyFile = Path(tempDir, "holder.ready")
                val waiterRanFile = Path(tempDir, "waiter.ran")
                val client = realTimeUnifiedExecToolClient(workingDirectory = tempDir)
                withWaitCleanup(
                    cleanup = {
                        try {
                            client.closeAndAwaitSessions()
                        } finally {
                            deleteDirectoryRecursively(tempDir)
                        }
                    },
                ) {
                    requireLinuxFlockAvailable(client)

                    val holderCommand =
                        "flock \"$lockFile\" sh -c " +
                            "'printf \"holder-locked\\n\" > \"$holderReadyFile\"; " +
                            "printf \"holder-locked\\n\"; " +
                            "IFS= read -r line; " +
                            "printf \"holder-released:%s\\n\" \"\$line\"'"
                    val holderInitial = client.execCommand(
                        ExecCommandArguments(
                            command = holderCommand,
                            shell = unifiedExecTestShell,
                            yieldTimeMillis = UnifiedExecMinimumYieldTimeMillis,
                        ),
                    )
                    val holderSessionId = assertNotNull(holderInitial.sessionId)
                    assertNull(holderInitial.exitCode)
                    awaitFileExists(holderReadyFile)

                    val waiterCommand =
                        "flock \"$lockFile\" sh -c " +
                            "'printf \"waiter-acquired\\n\" > \"$waiterRanFile\"; " +
                            "printf \"waiter-acquired\\n\"'"
                    val waiterDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                        client.execCommand(
                            ExecCommandArguments(
                                command = waiterCommand,
                                shell = unifiedExecTestShell,
                                yieldTimeMillis = TenMinuteYieldWindowMillis,
                            ),
                        )
                    }
                    client.activeSessions.first { sessions -> sessions.size == 2 }
                    delay(200.milliseconds)

                    assertFalse(waiterDeferred.isCompleted, "Waiter must remain blocked while lock is held.")
                    assertFalse(
                        SystemFileSystem.exists(waiterRanFile),
                        "Waiter command body must not execute while lock is held.",
                    )

                    val holderFinal = client.writeStdin(
                        WriteStdinArguments(
                            sessionId = holderSessionId,
                            chars = "release\n",
                            yieldTimeMillis = TenMinuteYieldWindowMillis,
                        ),
                    )
                    assertEquals(0, holderFinal.exitCode)
                    assertNull(holderFinal.sessionId)
                    assertTrue(
                        "holder-released:release" in (holderInitial.output + holderFinal.output),
                        "Holder output: ${holderInitial.output + holderFinal.output}",
                    )

                    val waiterResult = waiterDeferred.await()
                    assertEquals(0, waiterResult.exitCode)
                    assertNull(waiterResult.sessionId)
                    assertTrue("waiter-acquired" in waiterResult.output, "Waiter output: ${waiterResult.output}")
                    assertTrue(SystemFileSystem.exists(waiterRanFile))
                    assertTrue(client.activeSessions.value.isEmpty())
                }
                assertFalse(SystemFileSystem.exists(tempDir))
            }
        }

        test("cancelling a Linux private tempfile flock waiter unregisters the waiter without killing the lock holder") {
            withTimeout(RealTimeSmokeTimeout) {
                val tempDir = createPrivateTempDirectory("kodex-unified-exec-flock-cancel")
                val lockFile = Path(tempDir, "private.lock")
                val holderReadyFile = Path(tempDir, "holder.ready")
                val waiterRanFile = Path(tempDir, "waiter.ran")
                val client = realTimeUnifiedExecToolClient(workingDirectory = tempDir)
                withWaitCleanup(
                    cleanup = {
                        try {
                            client.closeAndAwaitSessions()
                        } finally {
                            deleteDirectoryRecursively(tempDir)
                        }
                    },
                ) {
                    requireLinuxFlockAvailable(client)

                    val holderCommand =
                        "flock \"$lockFile\" sh -c " +
                            "'printf \"holder-locked\\n\" > \"$holderReadyFile\"; " +
                            "printf \"holder-locked\\n\"; " +
                            "IFS= read -r line; " +
                            "printf \"holder-alive:%s\\n\" \"\$line\"'"
                    val holderInitial = client.execCommand(
                        ExecCommandArguments(
                            command = holderCommand,
                            shell = unifiedExecTestShell,
                            yieldTimeMillis = UnifiedExecMinimumYieldTimeMillis,
                        ),
                    )
                    val holderSessionId = assertNotNull(holderInitial.sessionId)
                    assertNull(holderInitial.exitCode)
                    awaitFileExists(holderReadyFile)

                    val waiterCommand =
                        "flock \"$lockFile\" sh -c " +
                            "'printf \"waiter-ran\\n\" > \"$waiterRanFile\"; " +
                            "printf \"waiter-ran\\n\"'"
                    val waiterDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                        client.execCommand(
                            ExecCommandArguments(
                                command = waiterCommand,
                                shell = unifiedExecTestShell,
                                yieldTimeMillis = TenMinuteYieldWindowMillis,
                            ),
                        )
                    }
                    val activeSnapshot = client.activeSessions.first { sessions -> sessions.size == 2 }
                    val waiterSessionId = (activeSnapshot.keys - holderSessionId).single()
                    val waiterJob = assertNotNull(
                        assertIs<ManagedProcessSession>(activeSnapshot.getValue(waiterSessionId))
                            .session.scope.coroutineContext[Job],
                    )
                    delay(200.milliseconds)

                    assertFalse(waiterDeferred.isCompleted)
                    assertFalse(SystemFileSystem.exists(waiterRanFile))

                    waiterDeferred.cancelAndJoin()
                    assertTrue(waiterDeferred.isCancelled)
                    // close() requests asynchronous termination; join owned cleanup before releasing the holder.
                    waiterJob.join()

                    assertEquals(setOf(holderSessionId), client.activeSessions.value.keys)
                    assertFalse(client.activeSessions.value.getValue(holderSessionId).completed.value)
                    assertFalse(SystemFileSystem.exists(waiterRanFile))
                    assertFailsWith<UnifiedExecToolException> {
                        client.writeStdin(
                            WriteStdinArguments(
                                sessionId = waiterSessionId,
                                chars = "",
                                yieldTimeMillis = UnifiedExecMinimumEmptyPollYieldTimeMillis,
                            ),
                        )
                    }

                    val holderFinal = client.writeStdin(
                        WriteStdinArguments(
                            sessionId = holderSessionId,
                            chars = "still-holding\n",
                            yieldTimeMillis = TenMinuteYieldWindowMillis,
                        ),
                    )
                    assertEquals(0, holderFinal.exitCode)
                    assertNull(holderFinal.sessionId)
                    assertTrue(
                        "holder-alive:still-holding" in (holderInitial.output + holderFinal.output),
                        "Holder output: ${holderInitial.output + holderFinal.output}",
                    )
                    assertFalse(SystemFileSystem.exists(waiterRanFile))
                    assertTrue(client.activeSessions.value.isEmpty())
                }
                assertFalse(SystemFileSystem.exists(tempDir))
            }
        }
    }
}
