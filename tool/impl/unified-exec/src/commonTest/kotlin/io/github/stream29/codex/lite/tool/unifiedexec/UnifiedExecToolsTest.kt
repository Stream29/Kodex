package io.github.stream29.codex.lite.tool.unifiedexec

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.openai.FunctionCallOutputBody
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiTool
import io.github.stream29.codex.lite.tool.builder.ToolBuilderJson
import io.github.stream29.codex.lite.tool.contract.Tool
import io.github.stream29.codex.lite.utils.shellclient.Shell
import io.github.stream29.codex.lite.utils.shellclient.ShellType
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.schema.json.StringPropertyDefinition
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val oneShotExecCommand: String = "echo codex-lite-unified-exec"

private val realIoTestConfig: TestConfig =
    TestConfig.testScope(isEnabled = false, timeout = 10.seconds)

private fun List<Tool>.toolNamed(name: String): Tool =
    single { tool -> (tool.spec as ResponsesApiTool).name == name }

private fun ResponseItem.ToolCallOutput.requireUnifiedExecOutput(): UnifiedExecOutput {
    val functionOutput = assertIs<ResponseItem.FunctionCallOutput>(this)
    assertTrue(
        functionOutput.output.success == true,
        "Unified exec tool failed: ${functionOutput.output.body}",
    )
    val text = assertIs<FunctionCallOutputBody.Text>(functionOutput.output.body).text
    return ToolBuilderJson.decodeFromString(UnifiedExecOutput.serializer(), text)
}

private suspend fun Tool.exec(arguments: ExecCommandArguments): ResponseItem.ToolCallOutput =
    handle(
        ResponseItem.FunctionCall(
            name = UnifiedExecTools.ExecCommandName,
            arguments = ToolBuilderJson.encodeToString(arguments),
            callId = "call_exec_command",
        ),
    )

private suspend fun Tool.execRaw(arguments: String): ResponseItem.ToolCallOutput =
    handle(
        ResponseItem.FunctionCall(
            name = UnifiedExecTools.ExecCommandName,
            arguments = arguments,
            callId = "call_exec_command",
        ),
    )

private suspend fun Tool.write(arguments: WriteStdinArguments): ResponseItem.ToolCallOutput =
    handle(
        ResponseItem.FunctionCall(
            name = UnifiedExecTools.WriteStdinName,
            arguments = ToolBuilderJson.encodeToString(arguments),
            callId = "call_write_stdin",
        ),
    )

val unifiedExecToolsTest by testSuite {
    test("specs declare the two Rust-compatible plain function tools") {
        assertEquals(UnifiedExecTools.ExecCommandName, UnifiedExecTools.execCommandSpec.name)
        assertEquals(UnifiedExecTools.WriteStdinName, UnifiedExecTools.writeStdinSpec.name)
        assertEquals(UnifiedExecOutputSchema, UnifiedExecTools.execCommandSpec.outputSchema)
        assertEquals(UnifiedExecOutputSchema, UnifiedExecTools.writeStdinSpec.outputSchema)
        assertTrue(UnifiedExecTools.execCommandSpec.parameters.required?.contains("cmd") == true)
        assertTrue(UnifiedExecTools.writeStdinSpec.parameters.required?.contains("session_id") == true)
        assertFalse(UnifiedExecTools.execCommandSpec.parameters.properties?.containsKey("login") == true)
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

    test("exec_command decodes a shell string into a path-preserving shell") {
        val arguments = ToolBuilderJson.decodeFromString<ExecCommandArguments>(
            """{"cmd":"echo test","shell":"/opt/codex/bash"}""",
        )

        assertEquals(
            Shell(type = ShellType.Bash, path = Path("/opt/codex/bash")),
            arguments.shell,
        )
    }

    testFixture { UnifiedExecToolClient() } closeWith { close() } asParameterForEach {
        test("exec_command returns JSON output from a real process", testConfig = realIoTestConfig) { client ->
            val tools = UnifiedExecTools.createTools(client)
            val exec = tools.toolNamed(UnifiedExecTools.ExecCommandName)
            val write = tools.toolNamed(UnifiedExecTools.WriteStdinName)

            var result = exec.exec(
                ExecCommandArguments(
                    command = oneShotExecCommand,
                    shell = unifiedExecTestShell,
                ),
            ).requireUnifiedExecOutput()
            val output = StringBuilder(result.output)
            var originalTokenCount = result.originalTokenCount
            repeat(10) {
                if (result.exitCode != null) return@repeat
                result = write.write(
                    WriteStdinArguments(
                        sessionId = assertNotNull(result.sessionId),
                        chars = "",
                    ),
                ).requireUnifiedExecOutput()
                output.append(result.output)
                originalTokenCount += result.originalTokenCount
            }

            assertEquals(0, result.exitCode)
            assertEquals(null, result.sessionId)
            assertTrue(output.contains("codex-lite-unified-exec"))
            assertTrue(originalTokenCount > 0)
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
            assertEquals(null, initial.exitCode)
            assertTrue(initial.output.contains("ready"))

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
                    ),
                ).requireUnifiedExecOutput()
                output += completed.output
            }

            assertEquals(0, completed.exitCode)
            assertEquals(null, completed.sessionId)
            assertTrue(output.contains("received:hello from stdin"))
        }
    }

    test("tty requests run in a pseudoterminal", testConfig = realIoTestConfig) {
        val client = UnifiedExecToolClient()
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
                    WriteStdinArguments(sessionId = assertNotNull(result.sessionId)),
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
        val client = UnifiedExecToolClient()
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
            assertTrue(initial.output.contains("ready"))

            var completed = write.write(
                WriteStdinArguments(
                    sessionId = sessionId,
                    chars = "hello from tty\n",
                    yieldTimeMillis = UnifiedExecMinimumYieldTimeMillis,
                ),
            ).requireUnifiedExecOutput()
            val output = StringBuilder(initial.output).append(completed.output)
            if (completed.exitCode == null) {
                completed = write.write(
                    WriteStdinArguments(sessionId = sessionId),
                ).requireUnifiedExecOutput()
                output.append(completed.output)
            }

            assertEquals(0, completed.exitCode)
            assertEquals(null, completed.sessionId)
            assertTrue(output.contains("received:hello from tty"))
        } finally {
            client.close()
        }
    }

    test("unsupported shell names report an explicit tool failure") {
        val client = UnifiedExecToolClient()
        try {
            val exec = UnifiedExecTools.createTools(client).toolNamed(UnifiedExecTools.ExecCommandName)
            val output = assertIs<ResponseItem.FunctionCallOutput>(
                exec.execRaw("""{"cmd":"echo test","shell":"unsupported-shell"}"""),
            )

            assertFalse(output.output.success == true)
            assertTrue((output.output.body as FunctionCallOutputBody.Text).text.contains("Unsupported shell"))
        } finally {
            client.close()
        }
    }

    test(
        "exec_command defaults to the client working directory",
        testConfig = TestConfig.testScope(isEnabled = true, timeout = 10.seconds),
    ) {
        val fileName = "codex-lite-unified-exec-cwd-${Random.nextLong()}.txt"
        val outputPath = Path(SystemTemporaryDirectory, fileName)
        val client = UnifiedExecToolClient(workingDirectory = SystemTemporaryDirectory)
        try {
            var result = client.execCommand(
                ExecCommandArguments(
                    command = "echo session-cwd > $fileName; echo cwd-written",
                    shell = unifiedExecTestShell,
                ),
            )
            val output = StringBuilder(result.output)
            withContext(Dispatchers.Default) {
                repeat(100) {
                    if (output.contains("cwd-written")) return@withContext
                    val sessionId = result.sessionId ?: return@withContext
                    delay(10.milliseconds)
                    result = client.writeStdin(WriteStdinArguments(sessionId))
                    output.append(result.output)
                }
            }

            assertTrue(output.contains("cwd-written"), "Command output: $output")
            assertTrue(
                SystemFileSystem.metadataOrNull(outputPath)?.isRegularFile == true,
                "Command output: $output",
            )
        } finally {
            client.close()
            SystemFileSystem.delete(outputPath, mustExist = false)
        }
    }

    test("yield returns a session for a still-running command", testConfig = realIoTestConfig) {
        val client = UnifiedExecToolClient()
        try {
            val exec = UnifiedExecTools.createTools(client).toolNamed(UnifiedExecTools.ExecCommandName)
            val output = exec.exec(
                ExecCommandArguments(
                    command = delayedExecCommand,
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

    test("cancellation closes and unregisters a running session", testConfig = realIoTestConfig) {
        val client = UnifiedExecToolClient()
        try {
            coroutineScope {
                val request = async(start = CoroutineStart.UNDISPATCHED) {
                    client.execCommand(
                        ExecCommandArguments(
                            command = delayedExecCommand,
                            shell = unifiedExecTestShell,
                            yieldTimeMillis = UnifiedExecMaximumYieldTimeMillis,
                        ),
                    )
                }
                delay(100.milliseconds)
                request.cancel()
                try {
                    request.await()
                    fail("Canceled exec_command completed normally.")
                } catch (_: CancellationException) {
                    // Expected: cancellation must close the child and discard its session entry.
                }
            }

            try {
                client.writeStdin(WriteStdinArguments(sessionId = 1))
                fail("Canceled process session remained registered.")
            } catch (_: UnifiedExecToolException) {
                // Expected: session identifiers are never reused after cancellation.
            }
        } finally {
            client.close()
        }
    }
}
