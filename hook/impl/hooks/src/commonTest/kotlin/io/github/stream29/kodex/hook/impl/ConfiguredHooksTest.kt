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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private suspend fun temporaryRoot(): Path =
    Path(SystemTemporaryDirectory, "kodex-hooks-${Random.nextLong()}").also {
        SystemCoroutineFileSystem.createDirectories(it)
    }.let {
        SystemCoroutineFileSystem.resolve(it)
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
    uri: String = "019c-session",
): HookTurnContext =
    HookTurnContext(
        session = HookSessionContext(
            uri = uri,
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

private fun recordStdinCommand(path: Path): String = when (Shell.default.type) {
    ShellType.Sh,
    ShellType.Bash,
    ShellType.Zsh,
        -> "cat > '${path.toString().replace("'", "'\"'\"'")}'"

    ShellType.PowerShell ->
        "\$null = [IO.File]::WriteAllText('${path.toString().replace("'", "''")}', [Console]::In.ReadToEnd())"

    ShellType.Cmd ->
        "powershell -NoProfile -NonInteractive -Command " +
            "\"[IO.File]::WriteAllText('${path.toString().replace("'", "''")}', [Console]::In.ReadToEnd())\""
}

private fun gatedHookCommand(started: Path, release: Path, completed: Path): String {
    fun Path.sh(): String = toString().replace("'", "'\"'\"'")
    fun Path.ps(): String = toString().replace("'", "''")
    val powershell = "\$null = [Console]::In.ReadToEnd(); " +
        "[IO.File]::WriteAllText('${started.ps()}', 'started'); " +
        "while (!(Test-Path '${release.ps()}')) { Start-Sleep -Milliseconds 10 }; " +
        "[IO.File]::WriteAllText('${completed.ps()}', 'completed')"
    return when (Shell.default.type) {
        ShellType.Sh, ShellType.Bash, ShellType.Zsh ->
            "cat >/dev/null; printf started > '${started.sh()}'; " +
                "while [ ! -f '${release.sh()}' ]; do sleep 0.01; done; " +
                "printf completed > '${completed.sh()}'"

        ShellType.PowerShell -> powershell
        ShellType.Cmd -> "powershell -NoProfile -NonInteractive -Command \"$powershell\""
    }
}

private suspend fun awaitHookFile(path: Path) {
    withTimeout(5.seconds) {
        while (!SystemCoroutineFileSystem.exists(path)) delay(10.milliseconds)
    }
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
        assertEquals("019c-session", input.getValue("uri").jsonPrimitive.content)
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

    test("UnhandledError receives plaintext stdin without JSON envelope, trailing newline, and closes stdin") {
        val root = temporaryRoot()
        val receivedFile = Path(root, "received.txt")
        val hooks = kodexHooks(
            hookConfiguration(
                Triple(
                    "error-recorder",
                    HookType.UnhandledError,
                    recordStdinCommand(receivedFile),
                ),
            ),
        )
        try {
            val message = "Something failed: 错误信息\nline 2 ' \" \$HOME \$(exit 7)"
            hooks.onUnhandledError(message, root)
            val readContent = SystemCoroutineFileSystem.readString(receivedFile)
            assertEquals(message, readContent)
        } finally {
            hooks.cancel()
            deleteRecursively(root)
        }
    }

    test("UnhandledError maps null message to empty string") {
        val root = temporaryRoot()
        val receivedFile = Path(root, "empty.txt")
        val hooks = kodexHooks(
            hookConfiguration(
                Triple(
                    "empty-recorder",
                    HookType.UnhandledError,
                    recordStdinCommand(receivedFile),
                ),
            ),
        )
        try {
            hooks.onUnhandledError(null, root)
            val readContent = SystemCoroutineFileSystem.readString(receivedFile)
            assertEquals("", readContent)
        } finally {
            hooks.cancel()
            deleteRecursively(root)
        }
    }

    test("UnhandledError swallows hook failure without throwing") {
        val root = temporaryRoot()
        val hooks = kodexHooks(
            hookConfiguration(
                Triple(
                    "failing-hook",
                    HookType.UnhandledError,
                    "exit 1",
                ),
            ),
        )
        try {
            hooks.onUnhandledError("error", root)
        } finally {
            hooks.cancel()
            deleteRecursively(root)
        }
    }

    test("UnhandledError keeps its configuration snapshot and isolates a failed parallel command") {
        val root = temporaryRoot()
        val started = Path(root, "started")
        val release = Path(root, "release")
        val completed = Path(root, "completed")
        val fresh = Path(root, "fresh")
        val settings = MutableStateFlow(
            TestHookSettings(
                hookConfiguration(
                    Triple("old", HookType.UnhandledError, gatedHookCommand(started, release, completed)),
                    Triple("failed-peer", HookType.UnhandledError, "exit 7"),
                ),
            ),
        )
        val hooks = CoroutineScope(currentCoroutineContext()).KodexHooksImpl(settings)
        val running = CoroutineScope(currentCoroutineContext()).async {
            hooks.onUnhandledError("old message", root)
        }
        try {
            awaitHookFile(started)
            settings.value = TestHookSettings(
                hookConfiguration(Triple("fresh", HookType.UnhandledError, recordStdinCommand(fresh))),
            )
            withTimeout(5.seconds) {
                hooks.resolvedHooks.first { it[HookType.UnhandledError].map { hook -> hook.name } == listOf("fresh") }
            }
            SystemCoroutineFileSystem.writeString(release, "")
            withTimeout(5.seconds) { running.await() }
            assertTrue(SystemCoroutineFileSystem.exists(completed))
            assertFalse(SystemCoroutineFileSystem.exists(fresh))
            hooks.onUnhandledError("new message", root)
            assertEquals("new message", SystemCoroutineFileSystem.readString(fresh))
        } finally {
            running.cancelAndJoin()
            hooks.cancel()
            deleteRecursively(root)
        }
    }

    test("UnhandledError propagates caller cancellation and stops its command") {
        val root = temporaryRoot()
        val started = Path(root, "started")
        val release = Path(root, "release")
        val completed = Path(root, "completed")
        val hooks = kodexHooks(
            hookConfiguration(
                Triple("cancelled", HookType.UnhandledError, gatedHookCommand(started, release, completed)),
            ),
        )
        val running = CoroutineScope(currentCoroutineContext()).async {
            hooks.onUnhandledError("cancel me", root)
        }
        try {
            awaitHookFile(started)
            withTimeout(5.seconds) { running.cancelAndJoin() }
            assertFailsWith<CancellationException> { running.await() }
            SystemCoroutineFileSystem.writeString(release, "")
            delay(100.milliseconds)
            assertFalse(SystemCoroutineFileSystem.exists(completed))
        } finally {
            running.cancelAndJoin()
            hooks.cancel()
            deleteRecursively(root)
        }
    }

    test("UnhandledError records a command startup failure without throwing") {
        val root = temporaryRoot()
        val hooks = kodexHooks(
            hookConfiguration(Triple("missing-cwd", HookType.UnhandledError, "exit 0")),
        )
        try {
            hooks.onUnhandledError("message", Path(root, "missing"))
        } finally {
            hooks.cancel()
            deleteRecursively(root)
        }
    }
}
