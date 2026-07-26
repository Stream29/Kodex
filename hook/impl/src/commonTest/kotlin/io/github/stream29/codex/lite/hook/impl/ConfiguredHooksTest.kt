package io.github.stream29.codex.lite.hook.impl

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.hook.contract.HookPermissionMode
import io.github.stream29.codex.lite.hook.contract.HookSessionContext
import io.github.stream29.codex.lite.hook.contract.tool.HookToolInvocation
import io.github.stream29.codex.lite.hook.contract.HookTurnContext
import io.github.stream29.codex.lite.hook.contract.tool.PreToolUseResult
import io.github.stream29.codex.lite.hook.contract.turn.StopRequest
import io.github.stream29.codex.lite.hook.contract.turn.StopResult
import io.github.stream29.codex.lite.hook.contract.turn.UserPromptSubmitRequest
import io.github.stream29.codex.lite.hook.contract.turn.UserPromptSubmitResult
import io.github.stream29.codex.lite.hook.impl.projection.HookJson
import io.github.stream29.codex.lite.hook.impl.projection.PermissionRequestCommandInputWire
import io.github.stream29.codex.lite.hook.impl.projection.PostToolUseCommandInputWire
import io.github.stream29.codex.lite.hook.impl.projection.PreToolUseCommandInputWire
import io.github.stream29.codex.lite.hook.impl.projection.SessionEndCommandInputWire
import io.github.stream29.codex.lite.hook.impl.projection.SessionStartCommandInputWire
import io.github.stream29.codex.lite.hook.impl.projection.StopCommandInputWire
import io.github.stream29.codex.lite.hook.impl.projection.UserPromptSubmitCommandInputWire
import io.github.stream29.codex.lite.hook.impl.projection.looksLikeJson
import io.github.stream29.codex.lite.hook.impl.projection.toPreToolUseResult
import io.github.stream29.codex.lite.hook.impl.projection.toStopResult
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliHookHandler
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliHookHandlers
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliHookLayer
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliHookMatcher
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliHookSourceKind
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliHookState
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.codex.lite.utils.shellclient.Shell
import io.github.stream29.codex.lite.utils.shellclient.ShellType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private suspend fun temporaryRoot(): Path =
    Path(SystemTemporaryDirectory, "codex-lite-hooks-${Random.nextLong()}").also {
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

private suspend fun codexHooks(configuration: HookConfiguration): CodexHooksImpl =
    CoroutineScope(currentCoroutineContext())
        .CodexHooksImpl(MutableStateFlow(TestHookSettings(configuration)))

private fun hookConfiguration(
    commands: List<String>,
    sourcePath: Path,
    groupHandlers: (List<CodexCliHookHandler>) -> CodexCliHookHandlers,
    environment: Map<String, String> = emptyMap(),
    timeoutSeconds: Long = 5L,
    additionalContextLimit: Int? = null,
): HookConfiguration {
    val path = sourcePath.toString()
    val handlers = commands.mapIndexed { index, command ->
        CodexCliHookHandler(
            key = "$path:$index",
            matcher = CodexCliHookMatcher.All,
            command = command,
            timeoutSeconds = timeoutSeconds,
            additionalContextLimit = additionalContextLimit,
        )
    }
    return HookConfiguration(
        sources = listOf(
            CodexCliHookLayer(
                sourcePath = sourcePath,
                sourceKind = CodexCliHookSourceKind.Project,
                managed = true,
                environment = environment,
                hooks = groupHandlers(handlers),
            ),
        ),
    )
}

private fun hookContext(
    cwd: Path,
    sessionId: String = "019c-session",
): HookTurnContext = HookTurnContext(
    session = HookSessionContext(
        sessionId = sessionId,
        cwd = cwd,
        model = "gpt-test",
        permissionMode = HookPermissionMode.Default,
    ),
    turnId = "turn-1",
)

private fun emitCommand(stdout: String, delayMilliseconds: Int = 0): String = when (Shell.default.type) {
    ShellType.Sh,
    ShellType.Bash,
    ShellType.Zsh,
        -> buildString {
        append("cat >/dev/null; ")
        if (delayMilliseconds > 0) append("sleep ${delayMilliseconds / 1_000.0}; ")
        append("printf '%s' '")
        append(stdout.replace("'", "'\"'\"'"))
        append("'")
    }

    ShellType.PowerShell -> buildString {
        append("\$null = [Console]::In.ReadToEnd(); ")
        if (delayMilliseconds > 0) append("Start-Sleep -Milliseconds $delayMilliseconds; ")
        append("[Console]::Out.Write('")
        append(stdout.replace("'", "''"))
        append("')")
    }

    ShellType.Cmd -> "more > nul && echo $stdout"
}

private fun requireInputAndEmitCommand(
    requiredInput: String,
    stdout: String,
): String = when (Shell.default.type) {
    ShellType.Sh,
    ShellType.Bash,
    ShellType.Zsh,
        -> buildString {
        append("input=\$(cat); case \"\$input\" in *'")
        append(requiredInput.replace("'", "'\"'\"'"))
        append("'*) ;; *) printf '%s' 'missing pipeline input' >&2; exit 2 ;; esac; printf '%s' '")
        append(stdout.replace("'", "'\"'\"'"))
        append("'")
    }

    ShellType.PowerShell -> buildString {
        append("\$input = [Console]::In.ReadToEnd(); if (-not \$input.Contains('")
        append(requiredInput.replace("'", "''"))
        append("')) { [Console]::Error.Write('missing pipeline input'); exit 2 }; [Console]::Out.Write('")
        append(stdout.replace("'", "''"))
        append("')")
    }

    ShellType.Cmd ->
        "findstr /C:\"stage\" > nul && echo $stdout || (echo missing pipeline input 1>&2 & exit /b 2)"
}

private fun exitTwoCommand(stderr: String): String = when (Shell.default.type) {
    ShellType.Sh,
    ShellType.Bash,
    ShellType.Zsh,
        -> "cat >/dev/null; printf '%s' '${stderr.replace("'", "'\"'\"'")}' >&2; exit 2"

    ShellType.PowerShell ->
        "\$null = [Console]::In.ReadToEnd(); [Console]::Error.Write('${stderr.replace("'", "''")}'); exit 2"

    ShellType.Cmd -> "more > nul && echo $stderr 1>&2 && exit /b 2"
}

private fun delayedCommand(delayMilliseconds: Int): String = when (Shell.default.type) {
    ShellType.Sh,
    ShellType.Bash,
    ShellType.Zsh,
        -> "cat >/dev/null; sleep ${delayMilliseconds / 1_000.0}"

    ShellType.PowerShell -> "\$null = [Console]::In.ReadToEnd(); Start-Sleep -Milliseconds $delayMilliseconds"
    ShellType.Cmd -> "more > nul && ping 127.0.0.1 -n 3 > nul"
}

private fun environmentCommand(name: String): String = when (Shell.default.type) {
    ShellType.Sh,
    ShellType.Bash,
    ShellType.Zsh,
        -> "cat >/dev/null; printf '%s' \"\$$name\""

    ShellType.PowerShell -> "\$null = [Console]::In.ReadToEnd(); [Console]::Out.Write(\$env:$name)"
    ShellType.Cmd -> "more > nul && echo %$name%"
}

val configuredHooksTest by testSuite(
    compartment = { TestCompartment.RealTime },
) {
    test("strict output codecs reject unknown fields") {
        assertEquals(
            StopResult.Finish,
            HookRawResult(
                exitCode = 0,
                stdout = """{"decision":"block","reason":"continue","extra":true}""",
                stderr = "",
            ).toStopResult("hook-run"),
        )
        assertEquals(
            PreToolUseResult.Continue(),
            HookRawResult(exitCode = 0, stdout = "[]", stderr = "").toPreToolUseResult(),
        )
        assertTrue("  [1]".looksLikeJson())
    }

    test("command inputs always encode their fixed event discriminator") {
        val context = hookContext(Path("hook-wire-test"))
        val session = context.session
        val encoded = listOf(
            "PreToolUse" to HookJson.encodeToString(
                PreToolUseCommandInputWire(
                    sessionId = session.sessionId,
                    turnId = context.turnId,
                    transcriptPath = null,
                    cwd = session.cwd.toString(),
                    model = session.model,
                    permissionMode = session.permissionMode.wireName,
                    toolName = "shell",
                    toolInput = JsonObject(emptyMap()),
                    toolUseId = "call-1",
                ),
            ),
            "PermissionRequest" to HookJson.encodeToString(
                PermissionRequestCommandInputWire(
                    sessionId = session.sessionId,
                    turnId = context.turnId,
                    transcriptPath = null,
                    cwd = session.cwd.toString(),
                    model = session.model,
                    permissionMode = session.permissionMode.wireName,
                    toolName = "shell",
                    toolInput = JsonObject(emptyMap()),
                ),
            ),
            "PostToolUse" to HookJson.encodeToString(
                PostToolUseCommandInputWire(
                    sessionId = session.sessionId,
                    turnId = context.turnId,
                    transcriptPath = null,
                    cwd = session.cwd.toString(),
                    model = session.model,
                    permissionMode = session.permissionMode.wireName,
                    toolName = "shell",
                    toolInput = JsonObject(emptyMap()),
                    toolResponse = JsonObject(emptyMap()),
                    toolUseId = "call-1",
                ),
            ),
            "SessionStart" to HookJson.encodeToString(
                SessionStartCommandInputWire(
                    sessionId = session.sessionId,
                    transcriptPath = null,
                    cwd = session.cwd.toString(),
                    model = session.model,
                    permissionMode = session.permissionMode.wireName,
                    source = "startup",
                ),
            ),
            "SessionEnd" to HookJson.encodeToString(
                SessionEndCommandInputWire(
                    sessionId = session.sessionId,
                    transcriptPath = null,
                    cwd = session.cwd.toString(),
                    model = session.model,
                    permissionMode = session.permissionMode.wireName,
                    reason = "shutdown",
                ),
            ),
            "UserPromptSubmit" to HookJson.encodeToString(
                UserPromptSubmitCommandInputWire(
                    sessionId = session.sessionId,
                    turnId = context.turnId,
                    transcriptPath = null,
                    cwd = session.cwd.toString(),
                    model = session.model,
                    permissionMode = session.permissionMode.wireName,
                    prompt = "hello",
                ),
            ),
            "Stop" to HookJson.encodeToString(
                StopCommandInputWire(
                    sessionId = session.sessionId,
                    turnId = context.turnId,
                    transcriptPath = null,
                    cwd = session.cwd.toString(),
                    model = session.model,
                    permissionMode = session.permissionMode.wireName,
                    stopHookActive = false,
                    lastAssistantMessage = null,
                ),
            ),
        )

        encoded.forEach { (eventName, json) ->
            assertEquals(
                eventName,
                HookJson.parseToJsonElement(json).jsonObject
                    .getValue("hook_event_name")
                    .jsonPrimitive
                    .content,
            )
        }
    }

    test("pre tool use accepts updated input only with permission allow") {
        val accepted = assertIs<PreToolUseResult.Continue>(
            HookRawResult(
                exitCode = 0,
                stdout = """{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"allow","updatedInput":{"command":"echo changed"}}}""",
                stderr = "",
            ).toPreToolUseResult(),
        )
        val rejected = assertIs<PreToolUseResult.Continue>(
            HookRawResult(
                exitCode = 0,
                stdout = """{"hookSpecificOutput":{"hookEventName":"PreToolUse","updatedInput":{"command":"echo changed"}}}""",
                stderr = "",
            ).toPreToolUseResult(),
        )

        assertEquals("echo changed", accepted.updatedInput?.jsonObject?.get("command")?.jsonPrimitive?.content)
        assertNull(rejected.updatedInput)
    }

    test("resolver executes unmanaged hooks without trust state and honors enabled state") {
        val source = hookConfiguration(
            commands = listOf(emitCommand("")),
            sourcePath = Path("codex-lite-hooks.json"),
            groupHandlers = { handlers -> CodexCliHookHandlers(stop = handlers) },
        ).sources.single()
        val enabled = HookConfiguration(sources = listOf(source.copy(managed = false)))
            .resolveHooks()
            .stop
            .single()
        assertEquals(source.hooks.stop.single(), enabled.definition)

        val disabled = HookConfiguration(
            sources = listOf(
                source.copy(managed = false),
                CodexCliHookLayer(
                    sourcePath = Path("codex-lite-hook-state.toml"),
                    sourceKind = CodexCliHookSourceKind.Session,
                    states = mapOf(
                        enabled.definition.key to CodexCliHookState(
                            enabled = false,
                            trustedHash = "sha256:ignored",
                        ),
                    ),
                ),
            ),
        ).resolveHooks()

        assertTrue(disabled.stop.isEmpty())
    }

    test("command hooks receive per-handler environment and produce context") {
        val root = temporaryRoot()
        val hooks = codexHooks(
            hookConfiguration(
                commands = listOf(environmentCommand("CODEXLITE_HOOK_VALUE")),
                sourcePath = Path(root, "hooks.json"),
                groupHandlers = { handlers -> CodexCliHookHandlers(userPromptSubmit = handlers) },
                environment = mapOf("CODEXLITE_HOOK_VALUE" to "environment-context"),
            ),
        )
        try {
            val result = hooks.onUserPromptSubmit(
                UserPromptSubmitRequest(hookContext(root), "hello"),
            )

            assertEquals(
                UserPromptSubmitResult.Continue(listOf("environment-context")),
                result,
            )
        } finally {
            hooks.cancel()
            deleteRecursively(root)
        }
    }

    test("stop exit code two becomes a continuation with separated stderr") {
        val root = temporaryRoot()
        val hooks = codexHooks(
            hookConfiguration(
                commands = listOf(
                    exitTwoCommand("continue from first hook"),
                    exitTwoCommand("continue from second hook"),
                ),
                sourcePath = Path(root, "hooks.json"),
                groupHandlers = { handlers -> CodexCliHookHandlers(stop = handlers) },
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

            assertEquals(
                listOf("continue from first hook", "continue from second hook"),
                result.fragments.map { it.text },
            )
            val sourcePath = Path(root, "hooks.json")
            assertEquals(
                listOf("$sourcePath:0|$sourcePath:1", "$sourcePath:0|$sourcePath:1"),
                result.fragments.map { it.hookRunId },
            )
        } finally {
            hooks.cancel()
            deleteRecursively(root)
        }
    }

    test("pre tool rewrites form a configured-order pipeline") {
        val root = temporaryRoot()
        val first =
            """{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"allow","updatedInput":{"stage":"first"}}}"""
        val second =
            """{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"allow","updatedInput":{"stage":"second"}}}"""
        val hooks = codexHooks(
            hookConfiguration(
                commands = listOf(
                    emitCommand(first, delayMilliseconds = 250),
                    requireInputAndEmitCommand(
                        requiredInput = "\"stage\":\"first\"",
                        stdout = second,
                    ),
                ),
                sourcePath = Path(root, "hooks.json"),
                groupHandlers = { handlers -> CodexCliHookHandlers(preToolUse = handlers) },
            ),
        )
        try {
            val result = assertIs<PreToolUseResult.Continue>(
                hooks.onPreToolUse(
                    HookToolInvocation(
                        context = hookContext(root),
                        toolName = "shell",
                        toolUseId = "call-1",
                        input = JsonObject(emptyMap()),
                    ),
                ),
            )

            assertEquals("second", result.updatedInput?.jsonObject?.get("stage")?.jsonPrimitive?.content)
        } finally {
            hooks.cancel()
            deleteRecursively(root)
        }
    }

    test("timeout fails open") {
        val root = temporaryRoot()
        val hooks = codexHooks(
            hookConfiguration(
                commands = listOf(delayedCommand(1_500)),
                sourcePath = Path(root, "hooks.json"),
                groupHandlers = { handlers -> CodexCliHookHandlers(userPromptSubmit = handlers) },
                timeoutSeconds = 1,
            ),
        )
        try {
            assertEquals(
                UserPromptSubmitResult.Continue(),
                hooks.onUserPromptSubmit(UserPromptSubmitRequest(hookContext(root), "hello")),
            )
        } finally {
            hooks.cancel()
            deleteRecursively(root)
        }
    }

    test("uses the latest global settings snapshot for each invocation") {
        val root = temporaryRoot()
        val settings = MutableStateFlow(
            TestHookSettings(HookConfiguration()),
        )
        val hooks = CoroutineScope(currentCoroutineContext()).CodexHooksImpl(settings)
        try {
            val request = UserPromptSubmitRequest(hookContext(root), "hello")
            assertEquals(
                UserPromptSubmitResult.Continue(),
                hooks.onUserPromptSubmit(request),
            )

            settings.value = TestHookSettings(
                hookConfiguration(
                    commands = listOf(emitCommand("updated-context")),
                    sourcePath = Path(root, "hooks.json"),
                    groupHandlers = { handlers -> CodexCliHookHandlers(userPromptSubmit = handlers) },
                ),
            )
            hooks.resolvedHooks.first { resolved -> resolved.userPromptSubmit.isNotEmpty() }

            assertEquals(
                UserPromptSubmitResult.Continue(listOf("updated-context")),
                hooks.onUserPromptSubmit(request),
            )
        } finally {
            hooks.cancel()
            deleteRecursively(root)
        }
    }

    test("hooks lifecycle follows owner scope") {
        val owner = CoroutineScope(
            currentCoroutineContext() + SupervisorJob(currentCoroutineContext()[Job]),
        )
        val hooks = owner.CodexHooksImpl(
            MutableStateFlow(TestHookSettings(HookConfiguration())),
        )

        assertTrue(hooks.isActive)
        owner.cancel()
        assertFalse(hooks.isActive)
    }

    test("factory requires an owner job") {
        val owner = object : CoroutineScope {
            override val coroutineContext: CoroutineContext = EmptyCoroutineContext
        }

        assertFailsWith<IllegalArgumentException> {
            owner.CodexHooksImpl(
                MutableStateFlow(TestHookSettings(HookConfiguration())),
            )
        }
    }

    test("additional context is passed through unchanged") {
        val root = temporaryRoot()
        val context = "handler-context-".repeat(128)
        val hooks = codexHooks(
            hookConfiguration(
                commands = listOf(emitCommand(context)),
                sourcePath = Path(root, "hooks.json"),
                groupHandlers = { handlers -> CodexCliHookHandlers(userPromptSubmit = handlers) },
                additionalContextLimit = 1,
            ),
        )
        try {
            assertEquals(
                UserPromptSubmitResult.Continue(listOf(context)),
                hooks.onUserPromptSubmit(
                    UserPromptSubmitRequest(hookContext(root), "hello"),
                ),
            )
        } finally {
            hooks.cancel()
            deleteRecursively(root)
        }
    }
}
