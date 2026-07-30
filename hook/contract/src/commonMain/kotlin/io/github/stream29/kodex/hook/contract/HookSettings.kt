package io.github.stream29.kodex.hook.contract

import io.github.stream29.kodex.openai.codexclistorage.CodexCliHookLayer
import kotlinx.serialization.Serializable

/** Narrow global-settings view required by a configured Hook implementation. */
public interface HookSettings {
    /** Latest complete Hook configuration snapshot. */
    public val hooks: HookConfiguration
}

/** Fully decoded Hook layers used to resolve one immutable executable snapshot. */
@Serializable
public data class HookConfiguration(
    public val featureEnabled: Boolean = true,
    public val sources: List<CodexCliHookLayer> = emptyList(),
)
