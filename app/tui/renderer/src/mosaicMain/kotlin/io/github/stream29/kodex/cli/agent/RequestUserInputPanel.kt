package io.github.stream29.kodex.cli.agent

import androidx.compose.runtime.*
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.onPlaced
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.app.agent.contract.RequestUserInputState
import io.github.stream29.kodex.app.agent.contract.RequestUserInputViewModel
import io.github.stream29.kodex.cli.components.*

@Composable
public actual fun RequestUserInputPanel(
    viewModel: RequestUserInputViewModel, state: RequestUserInputState.Pending, columns: Int, rows: Int,
) {
    if (rows <= 0) return
    RequestUserInputContent(viewModel, state, remember(columns, rows) { MosaicUserInputRenderer(columns, rows) })
}

private class MosaicUserInputRenderer(private val columns: Int, private val rows: Int) : UserInputRenderer {
    @Composable
    override fun Frame(
        callId: String, revision: Long, questionCount: Int, content: @Composable (UserInputRenderScope) -> Unit,
    ) {
        val scroll = remember(callId) { ScrollState() }
        val focus = remember(callId) { MosaicUserInputFocus(questionCount) }
        val controls = remember(callId, columns, focus) { MosaicUserInputScope(callId, columns, focus) }
        Column(Modifier.width(columns.coerceAtLeast(1)).height(rows).verticalScroll(scroll)) { content(controls) }
        SideEffect { focus.applyPending() }
        LaunchedEffect(focus.pending, revision) {
            if (focus.applyPending()) return@LaunchedEffect
            repeat(3) { withFrameNanos { }; if (focus.applyPending()) return@LaunchedEffect }
        }
    }
}

@Stable
private class MosaicUserInputFocus(count: Int) {
    val options = List(count) { FocusRequester() }
    val freeForms = List(count) { FocusRequester() }
    val submit = FocusRequester()
    var pending by mutableStateOf<UserInputFocusTarget?>(null)
    private fun requester(target: UserInputFocusTarget): FocusRequester? = when (target) {
        is UserInputFocusTarget.Option -> options.getOrNull(target.index)
        is UserInputFocusTarget.FreeForm -> freeForms.getOrNull(target.index)
        UserInputFocusTarget.Submit -> submit
    }
    fun request(target: UserInputFocusTarget) { pending = target; applyPending() }
    fun applyPending(): Boolean {
        val target = pending ?: return true
        if (requester(target)?.requestFocus() == true) { pending = null; return true }
        return false
    }
}

private class MosaicUserInputScope(
    private val callId: String, private val columns: Int, private val focus: MosaicUserInputFocus,
) : UserInputRenderScope {
    override fun requestFocus(target: UserInputFocusTarget) = focus.request(target)
    override fun question(index: Int, questionId: String): UserInputQuestionRenderer = MosaicUserInputQuestionRenderer(
        callId, questionId, columns, focus.options[index], focus.freeForms[index],
        focus.pending == UserInputFocusTarget.FreeForm(index), { focus.pending = null },
    )
    @Composable
    override fun Failure(message: String) { InteractionText(message, columns, textStyle = TextStyle.Dim) }
    @Composable
    override fun Submit(label: String, enabled: Boolean, onClick: () -> Unit) {
        TuiButton(
            label, Modifier.onPlaced { if (focus.pending == UserInputFocusTarget.Submit) focus.applyPending() },
            enabled = enabled, focusRequester = focus.submit, onClick = onClick,
        )
    }
}

internal class MosaicUserInputQuestionRenderer(
    private val callId: String,
    private val questionId: String,
    private val columns: Int,
    private val optionFocus: FocusRequester,
    private val freeFormFocus: FocusRequester,
    private val focusFreeFormOnPlacement: Boolean,
    private val onFreeFormFocusRequested: () -> Unit,
) : UserInputQuestionRenderer {
    @Composable
    override fun Header(text: String) { InteractionText(text, columns, textStyle = TextStyle.Bold) }
    @Composable
    override fun Option(
        label: String, description: String, selected: Boolean, enabled: Boolean,
        autoFocus: Boolean, first: Boolean, onClick: () -> Unit,
    ) {
        InteractionOption(
            label, description, columns, selected = selected, enabled = enabled, autoFocus = autoFocus,
            focusRequester = optionFocus.takeIf { first }, onClick = onClick,
        )
    }
    @Composable
    override fun Other(selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
        TuiButton("${if (selected) "●" else "○"} Other", enabled = enabled, onClick = onClick)
    }
    @Composable
    override fun FreeForm(
        text: String, enabled: Boolean, autoFocus: Boolean, onChange: (String) -> Boolean, onSubmitted: () -> Unit,
    ) {
        InteractionFreeForm(
            callId, questionId, text, columns, autoFocus, freeFormFocus,
            focusOnPlacement = focusFreeFormOnPlacement, enabled = enabled,
            onValueChanged = { onChange(it) }, onSubmitted = onSubmitted, onFocusRequested = onFreeFormFocusRequested,
        )
    }
}
