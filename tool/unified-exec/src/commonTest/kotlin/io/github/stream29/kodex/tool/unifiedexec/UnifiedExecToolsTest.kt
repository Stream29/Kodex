package io.github.stream29.kodex.tool.unifiedexec

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingCommandExecutionToolEvent
import io.github.stream29.kodex.openai.ResponsesApiTool
import io.github.stream29.kodex.tool.builder.ToolBuilderJson
import io.github.stream29.kodex.tool.contract.Tool
import io.github.stream29.kodex.utils.shellclient.ProcessSession
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellClient
import io.github.stream29.kodex.utils.shellclient.ShellProcessCommand
import io.github.stream29.kodex.utils.shellclient.ShellSettings
import io.github.stream29.kodex.utils.shellclient.ShellType
import io.github.stream29.kodex.utils.shellclient.StdoutBuffer
import io.github.stream29.kodex.utils.shellclient.StdoutBufferSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.schema.json.NumericPropertyDefinition
import kotlinx.schema.json.StringPropertyDefinition
import kotlinx.serialization.SerializationException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

private const val oneShotExecCommand: String = "echo kodex-unified-exec"

private val realIoTestConfig: TestConfig =
    // Interactive exec calls on Windows can consume the full 10-second minimum yield.
    TestConfig.testScope(isEnabled = false, timeout = 30.seconds)

private val phasedOutputExecCommand: String
    get() = if (unifiedExecTestShell.type == ShellType.PowerShell) {
        "Write-Output phase-one; Start-Sleep -Milliseconds 500; Write-Output phase-two"
    } else {
        "printf 'phase-one\\n'; sleep 0.5; printf 'phase-two\\n'"
    }

private data class TestShellSettings(
    override val shell: Shell = unifiedExecTestShell,
) : ShellSettings

private suspend fun testUnifiedExecToolClient(
    workingDirectory: Path = Path("."),
    settings: suspend () -> ShellSettings = { TestShellSettings() },
): UnifiedExecToolClient =
    CoroutineScope(currentCoroutineContext()).UnifiedExecToolClient(
        settingsProvider = settings,
        workingDirectoryProvider = { workingDirectory },
    )

private fun List<Tool>.toolNamed(name: String): Tool =
    single { tool -> (tool.spec as ResponsesApiTool).name == name }

private fun StableCommandExecutionToolEvent.requireUnifiedExecOutput(): UnifiedExecOutput =
    when (val completed = result) {
        is StableCommandExecutionResult.Output -> completed.value
        is StableCommandExecutionResult.Failure -> fail("Unified exec tool failed: ${completed.message}")
    }

private class CompletionTrackingProcessSession(
    override val scope: CoroutineScope,
) : ProcessSession {
    private val exitCodeDeferred: CompletableDeferred<Int> = CompletableDeferred()

    override val stdin: SendChannel<String> = Channel()
    override val stdout: StdoutBuffer = EmptyStdoutBuffer
    override val standardOutput: StdoutBuffer = EmptyStdoutBuffer
    override val standardError: StdoutBuffer = EmptyStdoutBuffer
    override val exitCode: Deferred<Int> = exitCodeDeferred

    fun complete(exitCode: Int) {
        exitCodeDeferred.complete(exitCode)
    }

    override fun close() {
        stdin.close()
        scope.cancel()
    }
}

private data object EmptyStdoutBuffer : StdoutBuffer {
    override suspend fun drain(): StdoutBufferSnapshot = EmptyStdoutBufferSnapshot

    override suspend fun read(yieldTime: kotlin.time.Duration): StdoutBufferSnapshot = EmptyStdoutBufferSnapshot
}

private val EmptyStdoutBufferSnapshot: StdoutBufferSnapshot =
    StdoutBufferSnapshot(head = ByteArray(0), tail = ByteArray(0), omittedByteCount = 0)

private suspend fun Tool.exec(arguments: ExecCommandArguments): StableCommandExecutionToolEvent =
    assertIs<StableCommandExecutionToolEvent>(
        handle(
            PendingCommandExecutionToolEvent(
                callId = "call_exec_command",
                action = PendingCommandExecutionAction.ExecCommand(arguments),
            ),
        ),
    )

private suspend fun Tool.write(arguments: WriteStdinArguments): StableCommandExecutionToolEvent =
    assertIs<StableCommandExecutionToolEvent>(
        handle(
            PendingCommandExecutionToolEvent(
                callId = "call_write_stdin",
                action = PendingCommandExecutionAction.WriteStdin(arguments),
            ),
        ),
    )

val unifiedExecToolsTest by testSuite {
    test("managed sessions retain their original arguments and observe process completion") {
        coroutineScope {
            val processScope = CoroutineScope(
                currentCoroutineContext() + SupervisorJob(currentCoroutineContext()[Job]),
            )
            val session = CompletionTrackingProcessSession(processScope)
            val arguments = ExecCommandArguments(command = "echo managed-session", yieldTimeMillis = 10_000)
            val managed = ManagedProcessSession(
                sessionId = 1,
                arguments = arguments,
                session = session,
            )
            val observed: UnifiedExecProcessSession = managed

            try {
                assertEquals(1, observed.sessionId)
                assertSame(arguments, observed.arguments)
                assertFalse(observed.completed.value)

                session.complete(0)

                withTimeout(1.seconds) {
                    observed.completed.first { completed -> completed }
                }
                assertTrue(observed.completed.value)
            } finally {
                session.close()
            }
        }
    }

    test("specs declare the two Rust-compatible plain function tools") {
        assertEquals(UnifiedExecTools.ExecCommandName, UnifiedExecTools.execCommandSpec.name)
        assertEquals(UnifiedExecTools.WriteStdinName, UnifiedExecTools.writeStdinSpec.name)
        assertEquals(UnifiedExecOutputSchema, UnifiedExecTools.execCommandSpec.outputSchema)
        assertEquals(UnifiedExecOutputSchema, UnifiedExecTools.writeStdinSpec.outputSchema)
        assertEquals(listOf("cmd", "yield_time_ms"), UnifiedExecTools.execCommandSpec.parameters.required)
        assertEquals(listOf("session_id", "yield_time_ms"), UnifiedExecTools.writeStdinSpec.parameters.required)
        assertFalse(UnifiedExecTools.execCommandSpec.parameters.properties?.containsKey("login") == true)

        val writeYieldProperty = assertIs<NumericPropertyDefinition>(
            requireNotNull(WriteStdinParametersSchema.properties?.get("yield_time_ms")),
        )
        assertEquals(
            "Required for both non-empty writes and empty polls. Maximum time to wait for output " +
                "before returning while the process is still running. Calls return immediately when " +
                "the process exits sooner. Choose a value for each call from the expected remaining " +
                "duration. Effective range is 250-600000 ms for non-empty writes and 5000-600000 ms " +
                "for empty polls.",
            writeYieldProperty.description,
        )
        assertFalse(writeYieldProperty.description.orEmpty().contains("default", ignoreCase = true))
        assertFalse(writeYieldProperty.description.orEmpty().contains("omit", ignoreCase = true))
    }

    test("exec_command describes its shell parameter without host-specific state") {
        val shell = assertIs<StringPropertyDefinition>(
            requireNotNull(ExecCommandParametersSchema.properties?.get("shell")),
        )

        assertEquals(ExecCommandShellDescription, shell.description)
    }

    test("exec_command adds safety guidance only on Windows") {
        val windows = renderExecCommandDescription(ExecCommandHostPlatform.Windows)
        val macos = renderExecCommandDescription(ExecCommandHostPlatform.Macos)
        val linux = renderExecCommandDescription(ExecCommandHostPlatform.Linux)

        assertTrue(windows.startsWith(UnifiedExecTools.ExecCommandDescription))
        assertTrue("Windows safety rules:" in windows)
        assertEquals(UnifiedExecTools.ExecCommandDescription, macos)
        assertEquals(UnifiedExecTools.ExecCommandDescription, linux)
    }

    test("exec_command describes platform-specific yield timing exactly") {
        val windowsDescription =
            "Required. Maximum time to wait before returning a session ID for a still-running command. " +
                "Commands that finish sooner return immediately. Choose a value for each call from the " +
                "command's expected duration. Effective range on Windows is 10000-600000 ms."
        assertEquals(
            windowsDescription,
            renderExecCommandYieldTimeDescription(ExecCommandHostPlatform.Windows),
        )
        assertFalse(windowsDescription.contains("default", ignoreCase = true))
        assertFalse(windowsDescription.contains("omit", ignoreCase = true))

        val posixDescription =
            "Required. Maximum time to wait before returning a session ID for a still-running command. " +
                "Commands that finish sooner return immediately. Choose a value for each call from the " +
                "command's expected duration. Effective range is 250-600000 ms."
        assertEquals(
            posixDescription,
            renderExecCommandYieldTimeDescription(ExecCommandHostPlatform.Macos),
        )
        assertEquals(
            posixDescription,
            renderExecCommandYieldTimeDescription(ExecCommandHostPlatform.Linux),
        )
        assertFalse(posixDescription.contains("default", ignoreCase = true))
        assertFalse(posixDescription.contains("omit", ignoreCase = true))
    }

    test("exec_command and write_stdin clamp yield timing across platforms and poll modes") {
        assertEquals(600_000L, UnifiedExecMaximumYieldTimeMillis)
        assertEquals(600_000L, UnifiedExecMaximumEmptyPollYieldTimeMillis)

        val execCases = listOf(
            Triple(ExecCommandHostPlatform.Windows, 0L, 10_000L),
            Triple(ExecCommandHostPlatform.Windows, 250L, 10_000L),
            Triple(ExecCommandHostPlatform.Windows, 10_000L, 10_000L),
            Triple(ExecCommandHostPlatform.Windows, 30_000L, 30_000L),
            Triple(ExecCommandHostPlatform.Windows, 300_000L, 300_000L),
            Triple(ExecCommandHostPlatform.Windows, 600_000L, 600_000L),
            Triple(ExecCommandHostPlatform.Windows, 600_001L, 600_000L),
            Triple(ExecCommandHostPlatform.Windows, Long.MAX_VALUE, 600_000L),
            Triple(ExecCommandHostPlatform.Macos, 0L, 250L),
            Triple(ExecCommandHostPlatform.Macos, 250L, 250L),
            Triple(ExecCommandHostPlatform.Macos, 10_000L, 10_000L),
            Triple(ExecCommandHostPlatform.Macos, 30_000L, 30_000L),
            Triple(ExecCommandHostPlatform.Macos, 300_000L, 300_000L),
            Triple(ExecCommandHostPlatform.Macos, 600_000L, 600_000L),
            Triple(ExecCommandHostPlatform.Macos, 600_001L, 600_000L),
            Triple(ExecCommandHostPlatform.Macos, Long.MAX_VALUE, 600_000L),
            Triple(ExecCommandHostPlatform.Linux, 0L, 250L),
            Triple(ExecCommandHostPlatform.Linux, 250L, 250L),
            Triple(ExecCommandHostPlatform.Linux, 10_000L, 10_000L),
            Triple(ExecCommandHostPlatform.Linux, 30_000L, 30_000L),
            Triple(ExecCommandHostPlatform.Linux, 300_000L, 300_000L),
            Triple(ExecCommandHostPlatform.Linux, 600_000L, 600_000L),
            Triple(ExecCommandHostPlatform.Linux, 600_001L, 600_000L),
            Triple(ExecCommandHostPlatform.Linux, Long.MAX_VALUE, 600_000L),
        )
        execCases.forEach { (platform, requested, expected) ->
            assertEquals(
                expected,
                normalizedExecYieldTimeMillis(requestedMillis = requested, platform = platform),
                "exec_command yield normalization mismatch for platform=$platform requested=$requested",
            )
        }

        val writeCases = listOf(
            Triple(false, 0L, 250L),
            Triple(false, 250L, 250L),
            Triple(false, 5_000L, 5_000L),
            Triple(false, 30_000L, 30_000L),
            Triple(false, 300_000L, 300_000L),
            Triple(false, 600_000L, 600_000L),
            Triple(false, 600_001L, 600_000L),
            Triple(false, Long.MAX_VALUE, 600_000L),
            Triple(true, 0L, 5_000L),
            Triple(true, 250L, 5_000L),
            Triple(true, 5_000L, 5_000L),
            Triple(true, 30_000L, 30_000L),
            Triple(true, 300_000L, 300_000L),
            Triple(true, 600_000L, 600_000L),
            Triple(true, 600_001L, 600_000L),
            Triple(true, Long.MAX_VALUE, 600_000L),
        )
        writeCases.forEach { (isEmptyPoll, requested, expected) ->
            assertEquals(
                expected,
                normalizedWriteYieldTimeMillis(requestedMillis = requested, isEmptyPoll = isEmptyPoll),
                "write_stdin yield normalization mismatch for isEmptyPoll=$isEmptyPoll requested=$requested",
            )
        }
    }

    test("negative yield_time_ms continues to fail validation without starting or polling a process") {
        val noOpShellClient = object : ShellClient, CoroutineScope by CoroutineScope(SupervisorJob()) {
            override suspend fun start(command: ShellProcessCommand): ProcessSession =
                fail("Negative yield_time_ms should fail before starting a process.")

            override fun close() {
                cancel()
            }
        }
        val client = UnifiedExecToolClient(
            workingDirectoryProvider = { Path(".") },
            settingsProvider = { TestShellSettings() },
            shellClient = noOpShellClient,
        )
        try {
            assertFailsWith<UnifiedExecToolException> {
                client.execCommand(ExecCommandArguments(command = "echo test", yieldTimeMillis = -1))
            }
            assertFailsWith<UnifiedExecToolException> {
                client.writeStdin(WriteStdinArguments(sessionId = 1, chars = "input\n", yieldTimeMillis = -1))
            }
            assertFailsWith<UnifiedExecToolException> {
                client.writeStdin(WriteStdinArguments(sessionId = 1, chars = "", yieldTimeMillis = -1))
            }
        } finally {
            client.close()
        }
    }

    test("exec_command decodes a shell string into a path-preserving shell and requires yield_time_ms") {
        val arguments = ToolBuilderJson.decodeFromString<ExecCommandArguments>(
            """{"cmd":"echo test","shell":"/opt/codex/bash","yield_time_ms":10000}""",
        )

        assertEquals(
            Shell(type = ShellType.Bash, path = Path("/opt/codex/bash")),
            arguments.shell,
        )
        assertEquals(10_000L, arguments.yieldTimeMillis)

        assertFailsWith<SerializationException> {
            ToolBuilderJson.decodeFromString<ExecCommandArguments>("""{"cmd":"echo test"}""")
        }
        assertFailsWith<SerializationException> {
            ToolBuilderJson.decodeFromString<WriteStdinArguments>("""{"session_id":1,"chars":"hi"}""")
        }
    }

    testFixture { testUnifiedExecToolClient() } closeWith { close() } asParameterForEach {
        test("exec_command returns JSON output from a real process", testConfig = realIoTestConfig) { client ->
            val tools = UnifiedExecTools.createTools(client)
            val exec = tools.toolNamed(UnifiedExecTools.ExecCommandName)
            val result = exec.exec(
                ExecCommandArguments(
                    command = oneShotExecCommand,
                    shell = unifiedExecTestShell,
                    yieldTimeMillis = 10_000,
                ),
            ).requireUnifiedExecOutput()
            assertEquals(0, result.exitCode)
            assertEquals(null, result.sessionId)
            assertTrue(result.output.contains("kodex-unified-exec"))
            assertTrue(result.originalTokenCount > 0)
        }

        test("exec_command waits through separate output bursts", testConfig = realIoTestConfig) { client ->
            val result = client.execCommand(
                ExecCommandArguments(command = phasedOutputExecCommand, yieldTimeMillis = 3_000),
            )

            assertEquals(0, result.exitCode)
            assertEquals(null, result.sessionId)
            assertTrue(result.output.contains("phase-one"))
            assertTrue(result.output.contains("phase-two"))
            assertTrue(client.activeSessions.value.isEmpty())
        }

        test("write_stdin waits through separate output bursts", testConfig = realIoTestConfig) { client ->
            val initial = client.execCommand(
                ExecCommandArguments(
                    command = "$interactiveExecCommand; $phasedOutputExecCommand",
                    yieldTimeMillis = UnifiedExecMinimumYieldTimeMillis,
                ),
            )
            val result = client.writeStdin(
                WriteStdinArguments(
                    sessionId = assertNotNull(initial.sessionId),
                    chars = "continue\n",
                    yieldTimeMillis = 3_000,
                ),
            )

            assertEquals(0, result.exitCode)
            assertEquals(null, result.sessionId)
            assertTrue(result.output.contains("received:continue"))
            assertTrue(result.output.contains("phase-one"))
            assertTrue(result.output.contains("phase-two"))
        }

        test("deadline yields buffered output and empty poll collects the rest", testConfig = realIoTestConfig) { client ->
            val initial = client.execCommand(
                ExecCommandArguments(
                    command = "$interactiveExecCommand; $phasedOutputExecCommand",
                    yieldTimeMillis = UnifiedExecMinimumYieldTimeMillis,
                ),
            )
            val sessionId = assertNotNull(initial.sessionId)
            val partial = client.writeStdin(
                WriteStdinArguments(
                    sessionId = sessionId,
                    chars = "continue\n",
                    yieldTimeMillis = UnifiedExecMinimumYieldTimeMillis,
                ),
            )

            assertEquals(null, partial.exitCode)
            assertEquals(sessionId, partial.sessionId)
            assertTrue(partial.wallTimeSeconds >= 0.25)
            val result = client.writeStdin(
                WriteStdinArguments(
                    sessionId = sessionId,
                    yieldTimeMillis = UnifiedExecMinimumEmptyPollYieldTimeMillis,
                ),
            )

            assertEquals(0, result.exitCode)
            assertEquals(null, result.sessionId)
            assertEquals(1, Regex("phase-one").findAll(partial.output + result.output).count())
            assertEquals(1, Regex("phase-two").findAll(partial.output + result.output).count())
        }

        test("write_stdin continues a real shell session", testConfig = realIoTestConfig) { client ->
            val tools = UnifiedExecTools.createTools(client)
            val exec = tools.toolNamed(UnifiedExecTools.ExecCommandName)
            val write = tools.toolNamed(UnifiedExecTools.WriteStdinName)
            val initial = exec.exec(
                ExecCommandArguments(
                    command = interactiveExecCommand,
                    shell = unifiedExecTestShell,
                    yieldTimeMillis = UnifiedExecMinimumYieldTimeMillis,
                ),
            ).requireUnifiedExecOutput()

            val sessionId = assertNotNull(initial.sessionId)
            assertTrue(sessionId > 0)
            assertEquals(null, initial.exitCode)

            var completed = write.write(
                WriteStdinArguments(
                    sessionId = sessionId,
                    chars = "hello from stdin\n",
                    yieldTimeMillis = UnifiedExecMinimumYieldTimeMillis,
                ),
            ).requireUnifiedExecOutput()
            var output = initial.output + completed.output
            if (completed.exitCode == null) {
                completed = write.write(
                    WriteStdinArguments(
                        sessionId = sessionId,
                        chars = "",
                        yieldTimeMillis = UnifiedExecMinimumEmptyPollYieldTimeMillis,
                    ),
                ).requireUnifiedExecOutput()
                output += completed.output
            }

            assertEquals(0, completed.exitCode)
            assertEquals(null, completed.sessionId)
            assertTrue(output.contains("ready"))
            assertTrue(output.contains("received:hello from stdin"))
        }
    }

    test("tty requests run in a pseudoterminal", testConfig = realIoTestConfig) {
        val client = testUnifiedExecToolClient()
        try {
            val tools = UnifiedExecTools.createTools(client)
            val exec = tools.toolNamed(UnifiedExecTools.ExecCommandName)
            val write = tools.toolNamed(UnifiedExecTools.WriteStdinName)
            var result = exec.exec(
                ExecCommandArguments(
                    command = ttyProbeExecCommand,
                    shell = unifiedExecTestShell,
                    tty = true,
                    yieldTimeMillis = UnifiedExecMinimumYieldTimeMillis,
                ),
            ).requireUnifiedExecOutput()
            val output = StringBuilder(result.output)
            repeat(10) {
                if (result.exitCode != null) return@repeat
                result = write.write(
                    WriteStdinArguments(
                        sessionId = assertNotNull(result.sessionId),
                        yieldTimeMillis = UnifiedExecMinimumEmptyPollYieldTimeMillis,
                    ),
                ).requireUnifiedExecOutput()
                output.append(result.output)
            }

            assertEquals(0, result.exitCode)
            assertEquals(null, result.sessionId)
            assertTrue(output.contains("tty=yes"))
        } finally {
            client.close()
        }
    }

    test("tty sessions accept interactive input", testConfig = realIoTestConfig) {
        val client = testUnifiedExecToolClient()
        try {
            val tools = UnifiedExecTools.createTools(client)
            val exec = tools.toolNamed(UnifiedExecTools.ExecCommandName)
            val write = tools.toolNamed(UnifiedExecTools.WriteStdinName)
            val initial = exec.exec(
                ExecCommandArguments(
                    command = interactiveExecCommand,
                    shell = unifiedExecTestShell,
                    tty = true,
                    yieldTimeMillis = UnifiedExecMinimumYieldTimeMillis,
                ),
            ).requireUnifiedExecOutput()

            val sessionId = assertNotNull(initial.sessionId)
            assertEquals(null, initial.exitCode)

            var completed = write.write(
                WriteStdinArguments(
                    sessionId = sessionId,
                    chars = "hello from tty\n",
                    yieldTimeMillis = UnifiedExecMinimumYieldTimeMillis,
                ),
            ).requireUnifiedExecOutput()
            val output = StringBuilder(initial.output).append(completed.output)
            repeat(10) {
                if (completed.exitCode != null) return@repeat
                completed = write.write(
                    WriteStdinArguments(
                        sessionId = sessionId,
                        yieldTimeMillis = UnifiedExecMinimumEmptyPollYieldTimeMillis,
                    ),
                ).requireUnifiedExecOutput()
                output.append(completed.output)
            }

            assertEquals(0, completed.exitCode, "Command output: $output")
            assertEquals(null, completed.sessionId)
            assertTrue(output.contains("ready"), "Command output: $output")
            assertTrue(output.contains("received:hello from tty"), "Command output: $output")
        } finally {
            client.close()
        }
    }

    test("new processes use the current global shell", testConfig = realIoTestConfig) {
        val settings = MutableStateFlow<ShellSettings>(
            TestShellSettings(
                shell = unifiedExecTestShell.copy(
                    path = Path("kodex-missing-shell-${Random.nextLong()}"),
                ),
            ),
        )
        val client = testUnifiedExecToolClient(settings = { settings.value })
        try {
            settings.value = TestShellSettings()
            var output = client.execCommand(
                ExecCommandArguments(command = oneShotExecCommand, yieldTimeMillis = 10_000),
            )
            val text = StringBuilder(output.output)
            repeat(10) {
                if (output.exitCode != null) return@repeat
                output = client.writeStdin(
                    WriteStdinArguments(
                        sessionId = assertNotNull(output.sessionId),
                        yieldTimeMillis = UnifiedExecMinimumEmptyPollYieldTimeMillis,
                    ),
                )
                text.append(output.output)
            }

            assertEquals(0, output.exitCode)
            assertTrue(text.contains("kodex-unified-exec"))
        } finally {
            client.close()
        }
    }

    test(
        "exec_command defaults to the client working directory",
        testConfig = realIoTestConfig,
    ) {
        val fileName = "kodex-unified-exec-cwd-${Random.nextLong()}.txt"
        val outputPath = Path(SystemTemporaryDirectory, fileName)
        assertTrue(
            SystemFileSystem.metadataOrNull(SystemTemporaryDirectory)?.isDirectory == true,
            "Temporary directory does not exist: $SystemTemporaryDirectory",
        )
        val client = testUnifiedExecToolClient(workingDirectory = SystemTemporaryDirectory)
        try {
            val result = client.execCommand(
                ExecCommandArguments(
                    command = "echo session-cwd > $fileName; echo cwd-written",
                    shell = unifiedExecTestShell,
                    yieldTimeMillis = 10_000,
                ),
            )
            assertEquals(0, result.exitCode)
            assertEquals(null, result.sessionId)
            assertTrue(result.output.contains("cwd-written"), "Command output: ${result.output}")
            assertTrue(
                SystemFileSystem.metadataOrNull(outputPath)?.isRegularFile == true,
                "Command output: ${result.output}",
            )
        } finally {
            client.close()
            SystemFileSystem.delete(outputPath, mustExist = false)
        }
    }

    test("yield returns a session for a still-running command", testConfig = realIoTestConfig) {
        val client = testUnifiedExecToolClient()
        try {
            val exec = UnifiedExecTools.createTools(client).toolNamed(UnifiedExecTools.ExecCommandName)
            val output = exec.exec(
                ExecCommandArguments(
                    command = interactiveExecCommand,
                    shell = unifiedExecTestShell,
                    yieldTimeMillis = 0,
                ),
            ).requireUnifiedExecOutput()

            assertEquals(null, output.exitCode)
            assertNotNull(output.sessionId)
        } finally {
            client.close()
        }
    }

    test("owner cancellation closes the dedicated shell client") {
        val owner = CoroutineScope(
            currentCoroutineContext() + SupervisorJob(currentCoroutineContext()[Job]),
        )
        val client = owner.UnifiedExecToolClient(
            settingsProvider = { TestShellSettings() },
        )
        owner.cancel()

        try {
            client.execCommand(
                ExecCommandArguments(command = oneShotExecCommand, yieldTimeMillis = 10_000),
            )
            fail("Cancelled owner left its unified-exec shell client usable.")
        } catch (failure: UnifiedExecToolException) {
            assertTrue(failure.message.orEmpty().contains("closed", ignoreCase = true))
        }
    }

    test("cancellation closes and unregisters a running session", testConfig = realIoTestConfig) {
        val client = testUnifiedExecToolClient()
        try {
            val sessionId = coroutineScope {
                val request = async(start = CoroutineStart.UNDISPATCHED) {
                    client.execCommand(
                        ExecCommandArguments(
                            command = delayedExecCommand,
                            shell = unifiedExecTestShell,
                            yieldTimeMillis = UnifiedExecMaximumYieldTimeMillis,
                        ),
                    )
                }
                val sessionId = withTimeout(1.seconds) {
                    client.activeSessions.first { sessions -> sessions.isNotEmpty() }.keys.single()
                }
                request.cancel()
                try {
                    request.await()
                    fail("Canceled exec_command completed normally.")
                } catch (_: CancellationException) {
                    // Expected: cancellation must close the child and discard its session entry.
                }
                sessionId
            }

            try {
                client.writeStdin(WriteStdinArguments(sessionId = sessionId, yieldTimeMillis = 250))
                fail("Canceled process session remained registered.")
            } catch (_: UnifiedExecToolException) {
                // Expected: cancellation removes the session entry.
            }
        } finally {
            client.close()
        }
    }
}

val unifiedExecSessionControlIoTest by testSuite(
    compartment = { TestCompartment.RealTime },
) {
    test("closing an observable session terminates it and preserves its final read") {
        val client = testUnifiedExecToolClient()
        try {
            val initial = client.execCommand(
                ExecCommandArguments(
                    command = interactiveExecCommand,
                    shell = unifiedExecTestShell,
                    yieldTimeMillis = 0,
                ),
            )
            val sessionId = assertNotNull(initial.sessionId)
            val session = assertNotNull(client.activeSessions.value[sessionId])

            session.close()

            withTimeout(5.seconds) {
                session.completed.first { completed -> completed }
            }
            assertSame(session, client.activeSessions.value[sessionId])

            val final = client.writeStdin(
                WriteStdinArguments(
                    sessionId = sessionId,
                    yieldTimeMillis = 0,
                ),
            )
            assertNotNull(final.exitCode)
            assertFalse(sessionId in client.activeSessions.value)
        } finally {
            client.close()
        }
    }

    test("write_stdin reports closed process input as a tool failure") {
        val client = testUnifiedExecToolClient()
        try {
            val tools = UnifiedExecTools.createTools(client)
            val exec = tools.toolNamed(UnifiedExecTools.ExecCommandName)
            val write = tools.toolNamed(UnifiedExecTools.WriteStdinName)
            val initial = exec.exec(
                ExecCommandArguments(
                    command = interactiveExecCommand,
                    shell = unifiedExecTestShell,
                    yieldTimeMillis = 0,
                ),
            ).requireUnifiedExecOutput()
            val sessionId = assertNotNull(initial.sessionId)
            val session = assertNotNull(client.activeSessions.value[sessionId])
            val managed = assertIs<ManagedProcessSession>(session)

            managed.session.stdin.send("complete\n")
            withTimeout(5.seconds) {
                session.completed.first { completed -> completed }
            }

            val completed = write.write(
                WriteStdinArguments(
                    sessionId = sessionId,
                    chars = "\u0003",
                    yieldTimeMillis = 0,
                ),
            )
            val failure = assertIs<StableCommandExecutionResult.Failure>(completed.result)

            assertEquals("Process standard input is closed.", failure.message)
            assertFalse(sessionId in client.activeSessions.value)
        } finally {
            client.close()
        }
    }
}
