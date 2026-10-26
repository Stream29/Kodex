package io.github.stream29.kodex.cli.agent

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.agent.contract.SuggestedSessionConfiguration
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskState
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskViewModel
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationMenu
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import kotlinx.io.files.Path

public interface SuggestionRenderScope {
    @Composable public fun Header(text: String)
    @Composable public fun Task(name: String, prompt: String)
    @Composable public fun Configuration(menu: RuntimeConfigurationMenu, cwd: Path, enabled: Boolean, onBrowse: () -> Unit)
    @Composable public fun Decision(label: String, description: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit)
    @Composable public fun Feedback(text: String, enabled: Boolean, onChange: (String) -> Unit, onSubmitted: () -> Unit)
    @Composable public fun RejectSubmit(enabled: Boolean, onClick: () -> Unit)
    public fun focusFeedback()
}

public interface SuggestionRenderer {
    @Composable public fun Frame(callId: String, content: @Composable (SuggestionRenderScope) -> Unit)
}

/** Exact call/revision composition; borrows the Agent-owned child and its accepted operations. */
@Composable
public expect fun SuggestSubagentTaskContent(
    viewModel: SuggestSubagentTaskViewModel, state: SuggestSubagentTaskState.Pending,
    renderer: SuggestionRenderer, onBrowseWorkingDirectory: (String) -> Unit,
)

/** Pure menu projection/callbacks shared by embedded and host-mounted dropdowns. */
@Composable
public expect fun SuggestionConfigurationMenuContent(
    viewModel: SuggestSubagentTaskViewModel, state: SuggestSubagentTaskState.Pending,
    content: @Composable (RuntimeConfigurationMenu) -> Unit,
)

public expect fun suggestionModelOptions(models: List<ModelInfo>, configuration: SuggestedSessionConfiguration): List<OpenAiModelId>
