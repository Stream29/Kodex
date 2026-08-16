package io.github.stream29.kodex.hook.impl

import io.github.stream29.kodex.utils.shellclient.ShellClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.io.files.Path

internal suspend fun ShellClient.runHook(
    hook: ExecutableHook,
    inputJson: String,
    cwd: Path,
): HookRawResult {
    return runHookCommand(
        command = hook.command,
        inputJson = inputJson,
        cwd = cwd,
    )
}

internal suspend fun ShellClient.runHooks(
    hooks: List<ExecutableHook>,
    cwd: Path,
    inputJson: (ExecutableHook) -> String,
): List<HookRawResult> = coroutineScope {
    hooks
        .map { hook ->
            async {
                runHook(
                    hook = hook,
                    inputJson = inputJson(hook),
                    cwd = cwd,
                )
            }
        }
        .awaitAll()
}
