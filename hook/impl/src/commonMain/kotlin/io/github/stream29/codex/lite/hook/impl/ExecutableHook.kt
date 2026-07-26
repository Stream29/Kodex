package io.github.stream29.codex.lite.hook.impl

import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliHookHandler
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliHookLayer
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliHookState

/** One enabled Hook definition paired with its source execution environment. */
internal data class ExecutableHook(
    val definition: CodexCliHookHandler,
    val environment: Map<String, String>,
)

internal data class ResolvedHooks(
    val preToolUse: List<ExecutableHook> = emptyList(),
    val permissionRequest: List<ExecutableHook> = emptyList(),
    val postToolUse: List<ExecutableHook> = emptyList(),
    val preCompact: List<ExecutableHook> = emptyList(),
    val postCompact: List<ExecutableHook> = emptyList(),
    val sessionStart: List<ExecutableHook> = emptyList(),
    val sessionEnd: List<ExecutableHook> = emptyList(),
    val userPromptSubmit: List<ExecutableHook> = emptyList(),
    val stop: List<ExecutableHook> = emptyList(),
)

internal fun HookConfiguration.resolveHooks(): ResolvedHooks {
    if (!featureEnabled) return ResolvedHooks()
    val states = buildMap {
        sources.forEach { source -> putAll(source.states) }
    }
    return ResolvedHooks(
        preToolUse = sources.resolveHandlers(states) { source -> source.hooks.preToolUse },
        permissionRequest = sources.resolveHandlers(states) { source -> source.hooks.permissionRequest },
        postToolUse = sources.resolveHandlers(states) { source -> source.hooks.postToolUse },
        preCompact = sources.resolveHandlers(states) { source -> source.hooks.preCompact },
        postCompact = sources.resolveHandlers(states) { source -> source.hooks.postCompact },
        sessionStart = sources.resolveHandlers(states) { source -> source.hooks.sessionStart },
        sessionEnd = sources.resolveHandlers(states) { source -> source.hooks.sessionEnd },
        userPromptSubmit = sources.resolveHandlers(states) { source -> source.hooks.userPromptSubmit },
        stop = sources.resolveHandlers(states) { source -> source.hooks.stop },
    )
}

private inline fun List<CodexCliHookLayer>.resolveHandlers(
    states: Map<String, CodexCliHookState>,
    handlers: (CodexCliHookLayer) -> List<CodexCliHookHandler>,
): List<ExecutableHook> = flatMap { source ->
    handlers(source)
        .filter { handler ->
            source.managed || states[handler.key]?.enabled != false
        }
        .map { handler ->
            ExecutableHook(
                definition = handler,
                environment = source.environment,
            )
        }
}
