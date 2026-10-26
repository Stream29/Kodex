@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.app.test

import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.files.Path

public actual abstract class SuggestionTestViewModel actual constructor() : SuggestSubagentTaskViewModel {
    public actual abstract override val state: MutableStateFlow<SuggestSubagentTaskState>
    public actual override val models: MutableStateFlow<List<ModelInfo>> = MutableStateFlow(emptyList())

    actual override fun setRejecting(callId: String, rejecting: Boolean): Boolean {
        val current = current(callId) ?: return false
        if (current.rejecting != rejecting) {
            state.value = current.copy(rejecting = rejecting, revision = current.revision + 1)
        }
        return true
    }
    actual override fun updateModelConfiguration(
        callId: String, model: OpenAiModelId, reasoningEffort: ReasoningEffort, serviceTier: ServiceTier,
    ): Boolean {
        val current = current(callId) ?: return false
        return updateConfiguration(callId, current.configuration.copy(
            model = model, reasoningEffort = reasoningEffort, serviceTier = serviceTier,
        ))
    }
    actual override fun updateRequestUserInputMode(callId: String, mode: RequestUserInputMode): Boolean {
        val current = current(callId) ?: return false
        return updateConfiguration(callId, current.configuration.copy(requestUserInputMode = mode))
    }
    actual override fun updateWorkingDirectory(callId: String, directory: Path): Boolean {
        val current = current(callId) ?: return false
        return updateConfiguration(callId, current.configuration.copy(cwd = directory))
    }
    private fun current(callId: String): SuggestSubagentTaskState.Pending? =
        (state.value as? SuggestSubagentTaskState.Pending)?.takeIf { it.callId == callId && !it.submitting }
}
