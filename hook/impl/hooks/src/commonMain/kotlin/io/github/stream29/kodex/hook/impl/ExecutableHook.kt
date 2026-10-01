package io.github.stream29.kodex.hook.impl

import io.github.stream29.kodex.hook.contract.HookConfiguration
import io.github.stream29.kodex.hook.contract.HookType

/** One named command ready for execution. */
internal data class ExecutableHook(
    val name: String,
    val command: String,
)

/** Hook commands grouped by type while preserving their configured order. */
internal class ResolvedHooks(
    private val hooks: Map<HookType, List<ExecutableHook>> = emptyMap(),
) {
    operator fun get(type: HookType): List<ExecutableHook> = hooks[type].orEmpty()
}

internal fun HookConfiguration.resolveHooks(): ResolvedHooks =
    ResolvedHooks(
        entries.groupBy(
            keySelector = { (_, body) -> body.type },
            valueTransform = { (name, body) ->
                require(name.isNotBlank()) { "A Hook name must not be blank." }
                ExecutableHook(
                    name = name,
                    command = body.command,
                )
            },
        ),
    )
