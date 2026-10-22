@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.app.test

import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskState
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskViewModel
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.io.files.Path

/** Host-test fixture for the expanded component commands; production never depends on this. */
public expect abstract class SuggestionTestViewModel() : SuggestSubagentTaskViewModel {
    public abstract override val state: MutableStateFlow<SuggestSubagentTaskState>
    public override val models: MutableStateFlow<List<ModelInfo>>
    override fun setRejecting(callId: String, rejecting: Boolean): Boolean
    override fun updateModelConfiguration(
        callId: String, model: OpenAiModelId, reasoningEffort: ReasoningEffort, serviceTier: ServiceTier,
    ): Boolean
    override fun updateRequestUserInputMode(callId: String, mode: RequestUserInputMode): Boolean
    override fun updateWorkingDirectory(callId: String, directory: Path): Boolean
}
