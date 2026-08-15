package io.github.stream29.kodex.hook.impl

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.hook.contract.HookConfiguration
import io.github.stream29.kodex.hook.contract.HookCommandDefinition
import io.github.stream29.kodex.hook.contract.HookDeclarations
import io.github.stream29.kodex.hook.contract.HookEnvironmentValue
import io.github.stream29.kodex.hook.contract.HookMatcherGroup
import io.github.stream29.kodex.hook.contract.HookPermissionMode
import io.github.stream29.kodex.hook.contract.HookSessionContext
import io.github.stream29.kodex.hook.contract.HookSourceConfiguration
import io.github.stream29.kodex.hook.contract.HookSettings
import io.github.stream29.kodex.hook.contract.tool.HookToolInvocation
import io.github.stream29.kodex.hook.contract.HookTurnContext
import io.github.stream29.kodex.hook.contract.tool.PreToolUseResult
import io.github.stream29.kodex.hook.contract.turn.StopRequest
import io.github.stream29.kodex.hook.contract.turn.StopResult
import io.github.stream29.kodex.hook.contract.turn.UserPromptSubmitRequest
import io.github.stream29.kodex.hook.contract.turn.UserPromptSubmitResult
import io.github.stream29.kodex.hook.impl.projection.HookJson
import io.github.stream29.kodex.hook.impl.projection.PermissionRequestCommandInputWire
import io.github.stream29.kodex.hook.impl.projection.PostToolUseCommandInputWire
import io.github.stream29.kodex.hook.impl.projection.PreToolUseCommandInputWire
import io.github.stream29.kodex.hook.impl.projection.StopCommandInputWire
import io.github.stream29.kodex.hook.impl.projection.UserPromptSubmitCommandInputWire
import io.github.stream29.kodex.hook.impl.projection.looksLikeJson
import io.github.stream29.kodex.hook.impl.projection.toPreToolUseResult
import io.github.stream29.kodex.hook.impl.projection.toStopResult
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellType
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

private fun hookConfiguration(
    commands: List<String>,
    sourcePath: Path,
    groupHandlers: (List<HookMatcherGroup>) -> HookDeclarations,
    environment: Map<String, String> = emptyMap(),
    timeoutSeconds: Long = 5L,
    additionalContextLimit: Int? = null,
): HookConfiguration {
    val handlers = commands.map { command ->
        HookCommandDefinition(
            command = command,
            timeoutSeconds = timeoutSeconds,
            additionalContextLimit = additionalContextLimit,
        )
    }
    val groups = listOf(HookMatcherGroup(hooks = handlers))
    return HookConfiguration(
        sources = listOf(
            HookSourceConfiguration(
                id = sourcePath.toString(),
                name = sourcePath.name,
                environment = environment.mapValues { (_, value) ->
                    HookEnvironmentValue(value)
                },
                hooks = groupHandlers(groups),
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
            PreToolUseResult.Continue,
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

    test("pre tool use ignores updated input and preserves deny") {
        assertEquals(
            PreToolUseResult.Continue,
            HookRawResult(
                exitCode = 0,
                stdout = """{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"allow","updatedInput":{"command":"echo changed"},"additionalContext":"ignored"}}""",
                stderr = "",
            ).toPreToolUseResult(),
        )
        assertEquals(
            PreToolUseResult.Block("denied"),
            HookRawResult(
                exitCode = 0,
                stdout = """{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"denied","updatedInput":{"command":"echo changed"}}}""",
                stderr = "",
            ).toPreToolUseResult(),
        )
        assertEquals(
            PreToolUseResult.Block("PreToolUse hook blocked this tool call."),
            HookRawResult(
                exitCode = 0,
                stdout = """{"decision":"block"}""",
                stderr = "",
            ).toPreToolUseResult(),
        )
    }

    test("resolver assigns branch-local handler identities") {
        val source = hookConfiguration(
            commands = listOf(emitCommand("")),
            sourcePath = Path("kodex-hooks.json"),
            groupHandlers = { groups -> HookDeclarations(stop = groups) },
        ).sources.single()
        val resolved = HookConfiguration(sources = listOf(source))
            .resolveHooks()
            .stop
            .single()
        assertEquals("${source.id}:Stop:0:0", resolved.id)
    }

    test("resolver honors feature source and command enablement") {
        val source = hookConfiguration(
            commands = listOf("first", "second"),
            sourcePath = Path("kodex-hooks.json"),
            groupHandlers = { groups -> HookDeclarations(stop = groups) },
        ).sources.single()

        assertEquals(
            emptyList(),
            HookConfiguration(
                featureEnabled = false,
                sources = listOf(source),
            ).resolveHooks().stop,
        )
        assertEquals(
            emptyList(),
            HookConfiguration(
                sources = listOf(source.copy(enabled = false)),
            ).resolveHooks().stop,
        )
        val handlers = source.hooks.stop.single().hooks
        val enabledSource = source.copy(
            hooks = source.hooks.copy(
                stop = listOf(
                    source.hooks.stop.single().copy(
                        hooks = listOf(
                            handlers.first().copy(enabled = false),
                            handlers.last(),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf("${source.id}:Stop:0:1"),
            HookConfiguration(sources = listOf(enabledSource))
                .resolveHooks()
                .stop
                .map(ExecutableHook::id),
        )
    }

    test("command hooks receive per-handler environment and produce context") {
        val root = temporaryRoot()
        val hooks = kodexHooks(
            hookConfiguration(
                commands = listOf(environmentCommand("KODEX_HOOK_VALUE")),
                sourcePath = Path(root, "hooks.json"),
                groupHandlers = { groups -> HookDeclarations(userPromptSubmit = groups) },
                environment = mapOf("KODEX_HOOK_VALUE" to "environment-context"),
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
        val hooks = kodexHooks(
            hookConfiguration(
                commands = listOf(
                    exitTwoCommand("continue from first hook"),
                    exitTwoCommand("continue from second hook"),
                ),
                sourcePath = Path(root, "hooks.json"),
                groupHandlers = { groups -> HookDeclarations(stop = groups) },
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
            val sourceId = Path(root, "hooks.json").toString()
            val hookRunId = "$sourceId:Stop:0:0|$sourceId:Stop:0:1"
            assertEquals(
                listOf(hookRunId, hookRunId),
                result.fragments.map { it.hookRunId },
            )
        } finally {
            hooks.cancel()
            deleteRecursively(root)
        }
    }

    test("pre tool hooks receive original input in order and stop on block") {
        val root = temporaryRoot()
        val first =
            """{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"allow","updatedInput":{"stage":"first"}}}"""
        val second =
            """{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"denied"}}"""
        val hooks = kodexHooks(
            hookConfiguration(
                commands = listOf(
                    emitCommand(first, delayMilliseconds = 250),
                    requireInputAndEmitCommand(
                        requiredInput = "\"stage\":\"original\"",
                        stdout = second,
                    ),
                ),
                sourcePath = Path(root, "hooks.json"),
                groupHandlers = { groups -> HookDeclarations(preToolUse = groups) },
            ),
        )
        try {
            assertEquals(
                PreToolUseResult.Block("denied"),
                hooks.onPreToolUse(
                    HookToolInvocation(
                        context = hookContext(root),
                        toolName = "shell",
                        toolUseId = "call-1",
                        input = JsonObject(mapOf("stage" to JsonPrimitive("original"))),
                    ),
                ),
            )
        } finally {
            hooks.cancel()
            deleteRecursively(root)
        }
    }

    test("timeout fails open") {
        val root = temporaryRoot()
        val hooks = kodexHooks(
            hookConfiguration(
                commands = listOf(delayedCommand(1_500)),
                sourcePath = Path(root, "hooks.json"),
                groupHandlers = { groups -> HookDeclarations(userPromptSubmit = groups) },
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
        val hooks = CoroutineScope(currentCoroutineContext()).KodexHooksImpl(settings)
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
                    groupHandlers = { groups -> HookDeclarations(userPromptSubmit = groups) },
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
        val hooks = owner.KodexHooksImpl(
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
            owner.KodexHooksImpl(
                MutableStateFlow(TestHookSettings(HookConfiguration())),
            )
        }
    }

    test("additional context is passed through unchanged") {
        val root = temporaryRoot()
        val context = "handler-context-".repeat(128)
        val hooks = kodexHooks(
            hookConfiguration(
                commands = listOf(emitCommand(context)),
                sourcePath = Path(root, "hooks.json"),
                groupHandlers = { groups -> HookDeclarations(userPromptSubmit = groups) },
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
