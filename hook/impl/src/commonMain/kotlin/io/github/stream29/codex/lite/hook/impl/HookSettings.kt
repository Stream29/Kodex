package io.github.stream29.codex.lite.hook.impl

import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliHookLayer
import kotlinx.serialization.Serializable

/** Narrow global-settings view required by the hook implementation. */
public interface HookSettings {
    /** Latest complete hook configuration snapshot. */
    public val hooks: HookConfiguration
}

/** Fully decoded Hook layers used to resolve one immutable executable snapshot. */
@Serializable
public data class HookConfiguration(
    public val featureEnabled: Boolean = true,
    public val sources: List<CodexCliHookLayer> = emptyList(),
)
