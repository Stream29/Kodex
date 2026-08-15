package io.github.stream29.kodex.hook.impl

import io.github.stream29.kodex.hook.contract.HookCommandDefinition
import io.github.stream29.kodex.hook.contract.HookConfiguration
import io.github.stream29.kodex.hook.contract.HookEvent
import io.github.stream29.kodex.hook.contract.HookMatcher
import io.github.stream29.kodex.hook.contract.HookSourceConfiguration

/** One enabled command Hook paired with source-dependent execution values. */
internal data class ExecutableHook(
    val id: String,
    val matcher: HookMatcher,
    val definition: HookCommandDefinition,
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
        preToolUse = sources.resolveHandlers(HookEvent.PreToolUse),
        permissionRequest = sources.resolveHandlers(HookEvent.PermissionRequest),
        postToolUse = sources.resolveHandlers(HookEvent.PostToolUse),
        preCompact = sources.resolveHandlers(HookEvent.PreCompact),
        postCompact = sources.resolveHandlers(HookEvent.PostCompact),
        userPromptSubmit = sources.resolveHandlers(HookEvent.UserPromptSubmit),
        stop = sources.resolveHandlers(HookEvent.Stop),
    )
}

private fun List<HookSourceConfiguration>.resolveHandlers(
    event: HookEvent,
): List<ExecutableHook> = flatMap { source ->
    if (!source.enabled) return@flatMap emptyList()
    source.hooks.groups(event).flatMapIndexed { groupIndex, group ->
        group.hooks.mapIndexedNotNull { handlerIndex, command ->
            if (!command.enabled) return@mapIndexedNotNull null
            ExecutableHook(
                id = "${source.id}:${event.wireName}:$groupIndex:$handlerIndex",
                matcher = group.matcher,
                definition = command,
                environment = source.environment.mapValues { (_, value) -> value.value },
            )
        }
    }
}
