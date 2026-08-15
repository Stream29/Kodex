package io.github.stream29.kodex.app.settings.contract

import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.openai.AgentMode
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import kotlinx.coroutines.flow.StateFlow

/** Frontend-ready persistent defaults rendered by Settings > New session. */
public data class NewSessionSettingsState(
    public val revision: Long,
    public val settings: KodexNewSessionSettings,
    public val modelOptions: List<OpenAiModelId>,
) {
    init {
        require(revision >= 0) { "A New Session Settings revision must not be negative." }
        require(modelOptions.distinct().size == modelOptions.size) {
            "New Session Settings model options must be unique."
        }
        require(settings.model in modelOptions) {
            "The default New Session model must be selectable."
        }
    }
}

/** Settings > New session state owner backed by global defaults. */
public interface NewSessionSettingsViewModel : AutoCloseable {
    public val state: StateFlow<NewSessionSettingsState>

    public fun updateModel(expectedRevision: Long, model: OpenAiModelId): Unit
    public fun updateReasoningEffort(expectedRevision: Long, reasoningEffort: ReasoningEffort): Unit
    public fun updateServiceTier(expectedRevision: Long, serviceTier: ServiceTier): Unit
    public fun updateAgentMode(expectedRevision: Long, agentMode: AgentMode): Unit
    public fun updateRequestUserInputMode(
        expectedRevision: Long,
        mode: RequestUserInputMode,
    ): Unit

    override fun close(): Unit
}
