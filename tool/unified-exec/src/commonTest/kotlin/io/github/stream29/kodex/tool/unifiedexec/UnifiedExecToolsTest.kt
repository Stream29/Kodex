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
import io.github.stream29.kodex.utils.shellclient.ProcessSession
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellClient
import io.github.stream29.kodex.utils.shellclient.ShellProcessCommand
import io.github.stream29.kodex.utils.shellclient.ShellSettings
import io.github.stream29.kodex.utils.shellclient.ShellType
import io.github.stream29.kodex.utils.shellclient.StdoutBuffer
import io.github.stream29.kodex.utils.shellclient.StdoutSlice
import io.github.stream29.kodex.utils.shellclient.renderedBytes
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
import kotlinx.schema.json.StringPropertyDefinition
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

private fun StableCommandExecutionToolEvent.requireUnifiedExecOutput(): UnifiedExecOutput =
    when (val completed = result) {
        is StableCommandExecutionResult.Output -> completed.value
        is StableCommandExecutionResult.Failure -> fail("Unified exec tool failed: ${completed.message}")
    }

private class CompletionTrackingProcessSession(
    override val scope: CoroutineScope,
) : ProcessSession {
    private val exitCodeDeferred: CompletableDeferred<Int> = CompletableDeferred()

    override val stdin: SendChannel<String> = Channel(Channel.UNLIMITED)
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
    override suspend fun read(offset: ULong, limit: UInt): StdoutSlice = EmptyStdoutSlice
}

private val EmptyStdoutSlice: StdoutSlice =
    StdoutSlice(
        head = ByteArray(0),
        tail = ByteArray(0),
        omittedByteCount = 0uL,
        offset = 0uL,
        nextOffset = 0uL,
        totalByteCount = 0uL,
    )

private suspend fun Tool.exec(arguments: ExecCommandArguments): StableCommandExecutionToolEvent =
    assertIs<StableCommandExecutionToolEvent>(
        handle(
            PendingCommandExecutionToolEvent(
                callId = "call_exec_command",
                action = PendingCommandExecutionAction.ExecCommand(arguments),
            ),
        ),
    )

val unifiedExecToolsTest by testSuite {
    test("managed sessions retain cwd and arguments and observe process exitCode") {
        coroutineScope {
            val processScope = CoroutineScope(
                currentCoroutineContext() + SupervisorJob(currentCoroutineContext()[Job]),
            )
            val session = CompletionTrackingProcessSession(processScope)
            val arguments = ExecCommandArguments(
                command = "echo managed-session",
                name = "managed-label",
                yieldTimeMillis = 0,
            )
            val cwd = Path("/workspace/project")
            val shellClient = object : ShellClient, CoroutineScope by processScope {
                override suspend fun start(command: ShellProcessCommand): ProcessSession = session
                override fun close() {
                    session.close()
                }
            }
            val client = UnifiedExecToolClient(
                workingDirectoryProvider = { cwd },
                settingsProvider = { TestShellSettings() },
                shellClient = shellClient,
            )

            try {
                val execCall = async(start = CoroutineStart.UNDISPATCHED) {
                    client.execCommand(arguments)
                }
                val observed = withTimeout(1.seconds) {
                    client.activeSessions.first { it.isNotEmpty() }.values.single()
                }
                assertEquals(cwd, observed.cwd)
                assertSame(arguments, observed.arguments)
                assertNull(observed.exitCode.value)

                session.complete(17)

                assertEquals(17, execCall.await().exitCode)
                assertEquals(17, observed.exitCode.value)
            } finally {
                client.close()
            }
        }
    }

    test("UnifiedExecTools exposes only exec_command model tool without write_stdin") {
        assertEquals(UnifiedExecTools.ExecCommandName, UnifiedExecTools.execCommandSpec.name)
        assertEquals(listOf("cmd", "yield_time_ms"), UnifiedExecTools.execCommandSpec.parameters.required)
        assertIs<StringPropertyDefinition>(
            requireNotNull(ExecCommandParametersSchema.properties?.get("name")),
        )
        assertFalse(UnifiedExecTools.execCommandSpec.parameters.properties?.containsKey("login") == true)
        assertFalse(UnifiedExecTools.execCommandSpec.parameters.properties?.containsKey("tty") == true)

        val noOpShellClient = object : ShellClient, CoroutineScope by CoroutineScope(SupervisorJob()) {
            override suspend fun start(command: ShellProcessCommand): ProcessSession =
                fail("Should not start a process while inspecting tool registration.")

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
            val tool = UnifiedExecTools.createTool(client)
            assertEquals("exec_command", (tool.spec as ResponsesApiTool).name)
        } finally {
            client.close()
        }
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

    test("exec_command clamps yield timing across platforms") {
        assertEquals(600_000L, UnifiedExecMaximumYieldTimeMillis)

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
    }

    test("negative yield_time_ms fails validation without starting a process") {
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
        } finally {
            client.close()
        }
    }

    test("exec_command decodes shell and optional name and requires yield_time_ms while retaining legacy WriteStdinArguments DTO") {
        val arguments = Json.decodeFromString<ExecCommandArguments>(
            """{"cmd":"echo test","name":"dev-server","shell":"/opt/codex/bash","yield_time_ms":10000}""",
        )

        assertEquals("dev-server", arguments.name)
        assertEquals(
            Shell(type = ShellType.Bash, path = Path("/opt/codex/bash")),
            arguments.shell,
        )
        assertEquals(10_000L, arguments.yieldTimeMillis)

        assertFailsWith<SerializationException> {
            Json.decodeFromString<ExecCommandArguments>("""{"cmd":"echo test"}""")
        }
        assertFailsWith<SerializationException> {
            Json.decodeFromString<WriteStdinArguments>("""{"session_id":1,"chars":"hi"}""")
        }
    }

    testFixture { testUnifiedExecToolClient() } closeWith { close() } asParameterForEach {
        test("exec_command returns JSON output from a real process", testConfig = realIoTestConfig) { client ->
            val exec = UnifiedExecTools.createTool(client)
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

        test("background session supports non-destructive cursor reads and writeStdin", testConfig = realIoTestConfig) { client ->
            val initial = client.execCommand(
                ExecCommandArguments(
                    command = interactiveExecCommand,
                    shell = unifiedExecTestShell,
                    yieldTimeMillis = UnifiedExecMinimumYieldTimeMillis,
                ),
            )

            val sessionId = assertNotNull(initial.sessionId)
            assertTrue(sessionId > 0)
            assertNull(initial.exitCode)

            val session = assertNotNull(client.activeSessions.value[sessionId])
            val initialSlice = session.readOutput(offset = 0uL)
            session.writeStdin("hello from stdin\n")

            val exitCode = withTimeout(10.seconds) {
                session.exitCode.first { code -> code != null }
            }
            assertEquals(0, exitCode)
            assertSame(session, client.activeSessions.value[sessionId])

            val fullOutput = session.readOutput(offset = 0uL).renderedBytes().decodeToString()
            val deltaOutput = session.readOutput(offset = initialSlice.nextOffset).renderedBytes().decodeToString()
            assertTrue(fullOutput.contains("ready"))
            assertTrue(fullOutput.contains("received:hello from stdin"))
            assertTrue(deltaOutput.contains("received:hello from stdin"))
        }
    }

    test("exec commands run in a pseudoterminal", testConfig = realIoTestConfig) {
        val client = testUnifiedExecToolClient()
        try {
            val exec = UnifiedExecTools.createTool(client)
            val result = exec.exec(
                ExecCommandArguments(
                    command = ttyProbeExecCommand,
                    shell = unifiedExecTestShell,
                    yieldTimeMillis = 10_000,
                ),
            ).requireUnifiedExecOutput()

            if (result.exitCode != null) {
                assertEquals(0, result.exitCode)
                assertTrue(result.output.contains("tty=yes"))
            } else {
                val session = assertNotNull(client.activeSessions.value[assertNotNull(result.sessionId)])
                assertEquals(0, withTimeout(10.seconds) { session.exitCode.first { it != null } })
                assertTrue(session.readOutput().renderedBytes().decodeToString().contains("tty=yes"))
            }
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
            val output = client.execCommand(
                ExecCommandArguments(command = oneShotExecCommand, yieldTimeMillis = 10_000),
            )
            if (output.exitCode != null) {
                assertEquals(0, output.exitCode)
                assertTrue(output.output.contains("kodex-unified-exec"))
            } else {
                val session = assertNotNull(client.activeSessions.value[assertNotNull(output.sessionId)])
                assertEquals(0, withTimeout(10.seconds) { session.exitCode.first { it != null } })
                assertTrue(session.readOutput().renderedBytes().decodeToString().contains("kodex-unified-exec"))
            }
        } finally {
            client.close()
        }
    }

    test(
        "exec_command defaults to the client working directory",
        testConfig = realIoTestConfig,
    ) {
        val tempRoot = Path(
            SystemTemporaryDirectory,
            "kodex-unified-exec-cwd-dir-${Random.nextLong()}",
        ).also { dir ->
            SystemFileSystem.createDirectories(dir, mustCreate = true)
        }.let(SystemFileSystem::resolve)
        val fileName = "kodex-unified-exec-cwd-${Random.nextLong()}.txt"
        val outputPath = Path(tempRoot, fileName)
        val client = testUnifiedExecToolClient(workingDirectory = tempRoot)
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
            SystemFileSystem.delete(tempRoot, mustExist = false)
        }
    }

    test("yield returns a session for a still-running command with resolved cwd", testConfig = realIoTestConfig) {
        val client = testUnifiedExecToolClient(workingDirectory = SystemTemporaryDirectory)
        try {
            val exec = UnifiedExecTools.createTool(client)
            val output = exec.exec(
                ExecCommandArguments(
                    command = interactiveExecCommand,
                    shell = unifiedExecTestShell,
                    yieldTimeMillis = 0,
                ),
            ).requireUnifiedExecOutput()

            assertNull(output.exitCode)
            val sessionId = assertNotNull(output.sessionId)
            val session = assertNotNull(client.activeSessions.value[sessionId])
            assertEquals(SystemTemporaryDirectory, session.cwd)
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

            assertFalse(sessionId in client.activeSessions.value)
        } finally {
            client.close()
        }
    }
}

val unifiedExecSessionControlIoTest by testSuite(
    compartment = { TestCompartment.RealTime },
) {
    test("closing an observable session terminates it while retaining exitCode and output in activeSessions") {
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

            val code = withTimeout(5.seconds) {
                session.exitCode.first { observed -> observed != null }
            }
            assertNotNull(code)
            assertSame(session, client.activeSessions.value[sessionId])
            // Non-destructive read still succeeds after exit during the retention window.
            session.readOutput()
        } finally {
            client.close()
        }
    }

    test("writeStdin on a completed session raises UnifiedExecToolException while keeping session retained") {
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

            session.writeStdin("complete\n", closeInput = true)
            withTimeout(5.seconds) {
                session.exitCode.first { observed -> observed != null }
            }

            val failure = assertFailsWith<UnifiedExecToolException> {
                session.writeStdin("\u0003")
            }
            assertEquals("Process standard input is closed.", failure.message)
            assertTrue(sessionId in client.activeSessions.value)
        } finally {
            client.close()
        }
    }

    test("execCommand forwards environmentProvider variables to spawned shell process") {
        val printEnvCommand = if (unifiedExecTestShell.type == ShellType.PowerShell) {
            "Write-Output \"\$env:KODEX_AGENT_ID|\$env:KODEX_HOME\""
        } else {
            "printf '%s|%s\\n' \"\$KODEX_AGENT_ID\" \"\$KODEX_HOME\""
        }
        val client = CoroutineScope(currentCoroutineContext()).UnifiedExecToolClient(
            settingsProvider = { TestShellSettings() },
            workingDirectoryProvider = { Path(".") },
            environmentProvider = {
                mapOf(
                    KodexAgentIdEnvironmentVariable to "42",
                    KodexHomeEnvironmentVariable to "/tmp/kodex-home-test",
                )
            },
        )
        try {
            val output = client.execCommand(
                ExecCommandArguments(
                    command = printEnvCommand,
                    shell = unifiedExecTestShell,
                    yieldTimeMillis = 5_000L,
                ),
            )
            assertEquals(0, output.exitCode)
            assertTrue("42|/tmp/kodex-home-test" in output.output, "Actual output: ${output.output}")
        } finally {
            client.close()
        }
    }
}
