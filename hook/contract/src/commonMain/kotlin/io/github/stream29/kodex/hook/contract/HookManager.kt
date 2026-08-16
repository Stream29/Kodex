package io.github.stream29.kodex.hook.contract

import kotlinx.coroutines.flow.StateFlow

/** Validated manager input for one complete Hook definition. */
public data class HookDraft(
    public val name: String,
    public val type: HookType,
    public val command: String,
)

/** Command-free Hook state safe for continuous Settings presentation. */
public data class HookManagedState(
    public val name: String,
    public val type: HookType,
) {
    init {
        require(name.isNotBlank()) { "A managed Hook name must not be blank." }
    }
}

/** Atomic persistence port over the Hook portion of global settings. */
public interface HookConfigurationStore {
    public val configuration: StateFlow<HookConfiguration>

    public suspend fun update(
        transform: (HookConfiguration) -> HookConfiguration,
    ): HookConfiguration
}

/** Application-wide Hook management authority. */
public interface HookManager : AutoCloseable {
    /** Hooks in their persisted execution order. */
    public val hooks: StateFlow<List<HookManagedState>>

    public suspend fun add(draft: HookDraft): String
    public suspend fun edit(name: String, draft: HookDraft): Unit
    public suspend fun delete(name: String): Unit

    /** Returns the complete definition only for an explicit editor request. */
    public fun editorDraft(name: String): HookDraft?

    override fun close(): Unit
}
