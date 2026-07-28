package io.github.stream29.codex.lite.hook.impl

import io.github.stream29.codex.lite.hook.contract.HookConfiguration
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliHookHandler
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliHookLayer
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliHookMatcher
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliHookMatcherGroup

/**
 * One enabled command Hook with all source-dependent values resolved.
 *
 * @property statusMessage Nullable because a command may not provide a UI
 * label; `null` means no custom label was configured.
 * @property additionalContextLimit Nullable because omission uses the default
 * output limit; `null` means no per-handler override was configured.
 */
internal data class ExecutableHook(
    val id: String,
    val matcher: CodexCliHookMatcher,
    val command: String,
    val timeoutSeconds: Long,
    val statusMessage: String?,
    val additionalContextLimit: Int?,
    val environment: Map<String, String>,
)

internal data class ResolvedHooks(
    val preToolUse: List<ExecutableHook> = emptyList(),
    val permissionRequest: List<ExecutableHook> = emptyList(),
    val postToolUse: List<ExecutableHook> = emptyList(),
    val preCompact: List<ExecutableHook> = emptyList(),
    val postCompact: List<ExecutableHook> = emptyList(),
    val userPromptSubmit: List<ExecutableHook> = emptyList(),
    val stop: List<ExecutableHook> = emptyList(),
)

internal fun HookConfiguration.resolveHooks(): ResolvedHooks {
    if (!featureEnabled) return ResolvedHooks()
    return ResolvedHooks(
        preToolUse = sources.resolveHandlers { it.hooks.preToolUse },
        permissionRequest = sources.resolveHandlers {
            it.hooks.permissionRequest
        },
        postToolUse = sources.resolveHandlers { it.hooks.postToolUse },
        preCompact = sources.resolveHandlers { it.hooks.preCompact },
        postCompact = sources.resolveHandlers { it.hooks.postCompact },
        userPromptSubmit = sources.resolveHandlers { it.hooks.userPromptSubmit },
        stop = sources.resolveHandlers { it.hooks.stop },
    )
}

private inline fun List<CodexCliHookLayer>.resolveHandlers(
    groups: (CodexCliHookLayer) -> List<CodexCliHookMatcherGroup>,
): List<ExecutableHook> = flatMap { source ->
    groups(source).flatMapIndexed { groupIndex, group ->
        group.hooks.mapIndexedNotNull { handlerIndex, handler ->
            val command = handler as? CodexCliHookHandler.Command
                ?: return@mapIndexedNotNull null
            val platformCommand = command.platformCommand
            if (command.async || platformCommand.isBlank()) {
                return@mapIndexedNotNull null
            }
            ExecutableHook(
                id = "${source.sourcePath}:$groupIndex:$handlerIndex",
                matcher = group.matcher,
                command = platformCommand.substituteEnvironment(source.environment),
                timeoutSeconds = (command.timeoutSeconds ?: DefaultHookTimeoutSeconds)
                    .coerceAtLeast(1L),
                statusMessage = command.statusMessage,
                additionalContextLimit = command.additionalContextLimit,
                environment = source.environment,
            )
        }
    }
}

private fun String.substituteEnvironment(environment: Map<String, String>): String =
    environment.entries.fold(this) { command, (key, value) ->
        command.replace("\${$key}", value)
    }

private const val DefaultHookTimeoutSeconds: Long = 600L
