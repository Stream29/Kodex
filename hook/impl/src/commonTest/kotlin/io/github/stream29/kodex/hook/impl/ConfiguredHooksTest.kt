package io.github.stream29.kodex.hook.impl

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.hook.contract.HookBody
import io.github.stream29.kodex.hook.contract.HookConfiguration
import io.github.stream29.kodex.hook.contract.HookSessionContext
import io.github.stream29.kodex.hook.contract.HookSettings
import io.github.stream29.kodex.hook.contract.HookTurnContext
import io.github.stream29.kodex.hook.contract.HookType
import io.github.stream29.kodex.hook.contract.tool.HookToolInvocation
import io.github.stream29.kodex.hook.contract.tool.PreToolUseResult
import io.github.stream29.kodex.hook.contract.turn.StopRequest
import io.github.stream29.kodex.hook.contract.turn.StopResult
import io.github.stream29.kodex.hook.contract.turn.UserPromptSubmitRequest
import io.github.stream29.kodex.hook.contract.turn.UserPromptSubmitResult
import io.github.stream29.kodex.hook.impl.projection.HookJson
import io.github.stream29.kodex.hook.impl.projection.PreToolUsePayload
import io.github.stream29.kodex.hook.impl.projection.encodeHookInput
import io.github.stream29.kodex.hook.impl.projection.toPreToolUseResult
import io.github.stream29.kodex.hook.impl.projection.toStopResult
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

private suspend fun temporaryRoot(): Path =
    Path(SystemTemporaryDirectory, "kodex-hooks-${Random.nextLong()}").also {
        SystemCoroutineFileSystem.createDirectories(it)
    }

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { child -> deleteRecursively(child) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}

private data class TestHookSettings(
    override val hooks: HookConfiguration,
) : HookSettings

private suspend fun kodexHooks(configuration: HookConfiguration): KodexHooksImpl =
    CoroutineScope(currentCoroutineContext())
        .KodexHooksImpl(MutableStateFlow(TestHookSettings(configuration)))

private fun hookContext(
    cwd: Path,
    sessionId: String = "019c-session",
): HookTurnContext =
    HookTurnContext(
        session = HookSessionContext(
            sessionId = sessionId,
            cwd = cwd,
            model = "gpt-test",
        ),
        turnId = "turn-1",
    )

private fun hookConfiguration(
    vararg hooks: Triple<String, HookType, String>,
): HookConfiguration =
    buildMap {
        hooks.forEach { (name, type, command) ->
            put(name, HookBody(type = type, command = command))
        }
    }

private fun emitCommand(stdout: String): String = when (Shell.default.type) {
    ShellType.Sh,
    ShellType.Bash,
    ShellType.Zsh,
        -> "cat >/dev/null; printf '%s' '${stdout.replace("'", "'\"'\"'")}'"

    ShellType.PowerShell ->
        "\$null = [Console]::In.ReadToEnd(); [Console]::Out.Write('${stdout.replace("'", "''")}')"

    ShellType.Cmd -> "more > nul & <nul set /p \"=$stdout\""
}

private fun recordAndEmitCommand(
    path: Path,
    marker: String,
    stdout: String,
): String = when (Shell.default.type) {
    ShellType.Sh,
    ShellType.Bash,
    ShellType.Zsh,
        -> "cat >/dev/null; printf '%s\\n' '${marker.replace("'", "'\"'\"'")}' >> " +
        "'${path.toString().replace("'", "'\"'\"'")}'; " +
        "printf '%s' '${stdout.replace("'", "'\"'\"'")}'"

    ShellType.PowerShell ->
        "\$null = [Console]::In.ReadToEnd(); " +
            "[IO.File]::AppendAllText('${path.toString().replace("'", "''")}', " +
            "'${marker.replace("'", "''")}' + [Environment]::NewLine); " +
            "[Console]::Out.Write('${stdout.replace("'", "''")}')"

    ShellType.Cmd -> "more > nul & echo $marker>>\"$path\" & <nul set /p \"=$stdout\""
}

val configuredHooksTest by testSuite(
    compartment = { TestCompartment.RealTime },
) {
    test("native input contains Hook identity, type, context, and payload") {
        val context = hookContext(Path("workspace"))
        val input = HookJson.parseToJsonElement(
            encodeHookInput(
                hook = ExecutableHook("guard tools", "ignored"),
                type = HookType.PreToolUse,
                context = context,
                payload = PreToolUsePayload(
                    toolName = "exec_command",
                    toolInput = JsonObject(mapOf("cmd" to JsonPrimitive("pwd"))),
                    toolUseId = "call-1",
                ),
            ),
        ).jsonObject

        assertEquals("guard tools", input.getValue("name").jsonPrimitive.content)
        assertEquals("pre_tool_use", input.getValue("type").jsonPrimitive.content)
        assertEquals("019c-session", input.getValue("session_id").jsonPrimitive.content)
        assertEquals("turn-1", input.getValue("turn_id").jsonPrimitive.content)
        assertEquals("gpt-test", input.getValue("model").jsonPrimitive.content)
        val payload = input.getValue("payload").jsonObject
        assertEquals("exec_command", payload.getValue("tool_name").jsonPrimitive.content)
        assertEquals("pwd", payload.getValue("tool_input").jsonObject.getValue("cmd").jsonPrimitive.content)
        assertFalse("hook_event_name" in input)
        assertFalse("transcript_path" in input)
        assertFalse("permission_mode" in input)
    }

    test("native outputs are strict and exit code two has no special meaning") {
        assertEquals(
            PreToolUseResult.Block("denied"),
            HookRawResult(
                exitCode = 0,
                stdout = """{"action":"block","reason":"denied"}""",
                stderr = "",
            ).toPreToolUseResult(),
        )
        assertEquals(
            PreToolUseResult.Continue,
            HookRawResult(
                exitCode = 0,
                stdout = """{"action":"block","reason":"denied","legacy":true}""",
                stderr = "",
            ).toPreToolUseResult(),
        )
        assertEquals(
            PreToolUseResult.Continue,
            HookRawResult(exitCode = 2, stdout = "", stderr = "legacy block")
                .toPreToolUseResult(),
        )
        assertEquals(
            StopResult.Finish,
            HookRawResult(exitCode = 2, stdout = "", stderr = "legacy continuation")
                .toStopResult("stop-check"),
        )
    }

    test("PreToolUse runs every configured Hook in order until block") {
        val root = temporaryRoot()
        val log = Path(root, "order.log")
        val commandLog = Path("order.log")
        val hooks = kodexHooks(
            hookConfiguration(
                Triple(
                    "first",
                    HookType.PreToolUse,
                    recordAndEmitCommand(commandLog, "first", """{"action":"continue"}"""),
                ),
                Triple(
                    "second",
                    HookType.PreToolUse,
                    recordAndEmitCommand(
                        commandLog,
                        "second",
                        """{"action":"block","reason":"blocked by second"}""",
                    ),
                ),
                Triple(
                    "third",
                    HookType.PreToolUse,
                    recordAndEmitCommand(commandLog, "third", """{"action":"continue"}"""),
                ),
            ),
        )
        try {
            val result = hooks.onPreToolUse(
                HookToolInvocation(
                    context = hookContext(root),
                    toolName = "any_tool",
                    toolUseId = "call-1",
                    input = JsonObject(emptyMap()),
                ),
            )

            assertEquals(PreToolUseResult.Block("blocked by second"), result)
            assertEquals(
                listOf("first", "second"),
                SystemCoroutineFileSystem.readString(log)
                    .lineSequence()
                    .filter(String::isNotBlank)
                    .toList(),
            )
        } finally {
            hooks.cancel()
            deleteRecursively(root)
        }
    }

    test("UserPromptSubmit accumulates context and stops the serial chain") {
        val root = temporaryRoot()
        val hooks = kodexHooks(
            hookConfiguration(
                Triple(
                    "first-context",
                    HookType.UserPromptSubmit,
                    emitCommand("""{"action":"continue","context":"first"}"""),
                ),
                Triple(
                    "block-prompt",
                    HookType.UserPromptSubmit,
                    emitCommand("""{"action":"block","reason":"rejected","context":"second"}"""),
                ),
                Triple(
                    "unused-context",
                    HookType.UserPromptSubmit,
                    emitCommand("""{"action":"continue","context":"third"}"""),
                ),
            ),
        )
        try {
            val result = assertIs<UserPromptSubmitResult.Stop>(
                hooks.onUserPromptSubmit(
                    UserPromptSubmitRequest(
                        context = hookContext(root),
                        prompt = "hello",
                    ),
                ),
            )

            assertEquals("rejected", result.reason)
            assertEquals(listOf("first", "second"), result.additionalContexts)
        } finally {
            hooks.cancel()
            deleteRecursively(root)
        }
    }

    test("Stop continuation uses the controlling Hook name") {
        val root = temporaryRoot()
        val hooks = kodexHooks(
            hookConfiguration(
                Triple(
                    "accept-first",
                    HookType.Stop,
                    emitCommand("""{"action":"finish"}"""),
                ),
                Triple(
                    "continue-review",
                    HookType.Stop,
                    emitCommand("""{"action":"continue","prompt":"review again"}"""),
                ),
                Triple(
                    "unused-stop",
                    HookType.Stop,
                    emitCommand("""{"action":"stop","reason":"unused"}"""),
                ),
            ),
        )
        try {
            val result = assertIs<StopResult.Continue>(
                hooks.onStop(
                    StopRequest(
                        context = hookContext(root),
                        stopHookActive = false,
                        lastAssistantMessage = "done",
                    ),
                ),
            )

            assertEquals("review again", result.fragments.single().text)
            assertEquals("continue-review", result.fragments.single().hookRunId)
        } finally {
            hooks.cancel()
            deleteRecursively(root)
        }
    }
}
