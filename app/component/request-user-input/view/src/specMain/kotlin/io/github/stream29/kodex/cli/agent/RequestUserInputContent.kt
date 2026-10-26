package io.github.stream29.kodex.cli.agent

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.agent.contract.RequestUserInputDraftAnswer
import io.github.stream29.kodex.app.agent.contract.RequestUserInputState
import io.github.stream29.kodex.app.agent.contract.RequestUserInputViewModel
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion

public sealed interface UserInputFocusTarget {
    public data class Option(public val index: Int) : UserInputFocusTarget
    public data class FreeForm(public val index: Int) : UserInputFocusTarget
    public data object Submit : UserInputFocusTarget
}

/** Native/graphical primitives; controlled answer values never become a renderer-owned draft. */
public interface UserInputQuestionRenderer {
    @Composable public fun Header(text: String)
    @Composable public fun Option(
        label: String, description: String, selected: Boolean, enabled: Boolean,
        autoFocus: Boolean, first: Boolean, onClick: () -> Unit,
    )
    @Composable public fun Other(selected: Boolean, enabled: Boolean, onClick: () -> Unit)
    @Composable public fun FreeForm(
        text: String, enabled: Boolean, autoFocus: Boolean,
        onChange: (String) -> Boolean, onSubmitted: () -> Unit,
    )
}

/** Renderer-only focus/scroll lifetime for one exact tool call. */
public interface UserInputRenderScope {
    public fun requestFocus(target: UserInputFocusTarget)
    public fun question(index: Int, questionId: String): UserInputQuestionRenderer
    @Composable public fun Failure(message: String)
    @Composable public fun Submit(label: String, enabled: Boolean, onClick: () -> Unit)
}

public interface UserInputRenderer {
    @Composable public fun Frame(
        callId: String, revision: Long, questionCount: Int,
        content: @Composable (UserInputRenderScope) -> Unit,
    )
}

/** Borrows the Agent's child. Unmount cancels caller waits, never an accepted owner operation. */
@Composable
public expect fun RequestUserInputContent(
    viewModel: RequestUserInputViewModel, state: RequestUserInputState.Pending, renderer: UserInputRenderer,
)

/** Shared question branches; callbacks are already bound to the caller's exact call/question. */
@Composable
public expect fun RequestUserInputQuestionContent(
    question: RequestUserInputQuestion, draft: RequestUserInputDraftAnswer?,
    renderer: UserInputQuestionRenderer, enabled: Boolean, autoFocus: Boolean,
    onSelectOption: (String) -> Boolean, onSelectOther: () -> Boolean,
    onFreeFormChanged: (String) -> Boolean, onFreeFormSubmitted: () -> Unit,
)
