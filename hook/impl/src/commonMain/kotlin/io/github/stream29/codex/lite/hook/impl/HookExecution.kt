package io.github.stream29.codex.lite.hook.impl

import io.github.stream29.codex.lite.utils.shellclient.ShellClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.io.files.Path
import kotlin.time.Duration.Companion.seconds

internal suspend fun ShellClient.runHook(
    hook: ExecutableHook,
    inputJson: String,
    cwd: Path,
): HookRawResult {
    val definition = hook.definition
    return runHook(
        command = definition.command,
        inputJson = inputJson,
        cwd = cwd,
        environment = hook.environment,
        timeout = definition.timeoutSeconds.seconds,
    )
}

internal suspend fun ShellClient.runHooks(
    hooks: List<ExecutableHook>,
    inputJson: String,
    cwd: Path,
): List<HookRawResult> = coroutineScope {
    hooks
        .map { hook ->
            async {
                runHook(
                    hook = hook,
                    inputJson = inputJson,
                    cwd = cwd,
                )
            }
        }
        .awaitAll()
}

internal fun List<ExecutableHook>.matching(
    matcherInputs: List<String>,
): List<ExecutableHook> =
    filter { hook -> hook.definition.matcher.matches(matcherInputs) }
