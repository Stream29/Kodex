package io.github.stream29.kodex.app.test

import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.files.Path

/** Host-test fixture for the expanded component commands; production never depends on this. */
public abstract class SuggestionTestViewModel : SuggestSubagentTaskViewModel {
    public abstract override val state: MutableStateFlow<SuggestSubagentTaskState>
    public override val models: MutableStateFlow<List<ModelInfo>> = MutableStateFlow(emptyList())

    override fun setRejecting(callId: String, rejecting: Boolean): Boolean {
        val current = current(callId) ?: return false
        if (current.rejecting != rejecting) {
            state.value = current.copy(rejecting = rejecting, revision = current.revision + 1)
        }
        return true
    }
    override fun updateModelConfiguration(
        callId: String, model: OpenAiModelId, reasoningEffort: ReasoningEffort, serviceTier: ServiceTier,
    ): Boolean {
        val current = current(callId) ?: return false
        return updateConfiguration(callId, current.configuration.copy(
            model = model, reasoningEffort = reasoningEffort, serviceTier = serviceTier,
        ))
    }
    override fun updateRequestUserInputMode(callId: String, mode: RequestUserInputMode): Boolean {
        val current = current(callId) ?: return false
        return updateConfiguration(callId, current.configuration.copy(requestUserInputMode = mode))
    }
    override fun updateWorkingDirectory(callId: String, directory: Path): Boolean {
        val current = current(callId) ?: return false
        return updateConfiguration(callId, current.configuration.copy(cwd = directory))
    }
    private fun current(callId: String): SuggestSubagentTaskState.Pending? =
        (state.value as? SuggestSubagentTaskState.Pending)?.takeIf { it.callId == callId && !it.submitting }
}
