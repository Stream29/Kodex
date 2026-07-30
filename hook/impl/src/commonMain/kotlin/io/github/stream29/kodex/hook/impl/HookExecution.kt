package io.github.stream29.kodex.hook.impl

import io.github.stream29.kodex.utils.shellclient.ShellClient
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
    return runHook(
        command = hook.command,
        inputJson = inputJson,
        cwd = cwd,
        environment = hook.environment,
        timeout = hook.timeoutSeconds.seconds,
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
    filter { hook -> hook.matcher.matches(matcherInputs) }
