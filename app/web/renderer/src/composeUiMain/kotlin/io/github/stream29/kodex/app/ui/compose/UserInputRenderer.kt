package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.ui.*
import io.github.stream29.kodex.cli.agent.*

@Composable
public actual fun rememberComposeUserInputRenderer(): UserInputRenderer = remember { ComposeUserInputRenderer() }

private class ComposeUserInputRenderer : UserInputRenderer {
    @Composable
    override fun Frame(
        callId: String, revision: Long, questionCount: Int, content: @Composable (UserInputRenderScope) -> Unit,
    ) {
        key(callId) {
            val freeForms = List(questionCount) { rememberUiEditorFocusHandle() }
            val focus = remember(questionCount) { ComposeUserInputFocus(questionCount, freeForms) }
            val controls = remember(focus) { ComposeUserInputScope(focus) }
            Column(
                Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { content(controls) }
            SideEffect { focus.applyPending() }
            LaunchedEffect(focus.pending, revision) {
                if (focus.applyPending()) return@LaunchedEffect
                repeat(3) { withFrameNanos { }; if (focus.applyPending()) return@LaunchedEffect }
            }
        }
    }
}

@Stable
private class ComposeUserInputFocus(count: Int, val freeForms: List<UiEditorFocusHandle>) {
    val options = List(count) { FocusRequester() }
    val submit = FocusRequester()
    var pending by mutableStateOf<UserInputFocusTarget?>(null)
    fun request(target: UserInputFocusTarget) { pending = target; applyPending() }
    fun applyPending(): Boolean {
        val target = pending ?: return true
        val focused = when (target) {
            is UserInputFocusTarget.Option -> options.getOrNull(target.index)?.tryRequest()
            is UserInputFocusTarget.FreeForm -> freeForms.getOrNull(target.index)?.requestFocus()
            UserInputFocusTarget.Submit -> submit.tryRequest()
        } == true
        if (focused) { pending = null; return true }
        return false
    }
}

private fun FocusRequester.tryRequest(): Boolean = try {
    requestFocus(FocusDirection.Enter)
} catch (_: IllegalStateException) {
    false
}

private class ComposeUserInputScope(private val focus: ComposeUserInputFocus) : UserInputRenderScope {
    override fun requestFocus(target: UserInputFocusTarget) = focus.request(target)
    override fun question(index: Int, questionId: String): UserInputQuestionRenderer =
        ComposeUserInputQuestion(questionId, focus.options[index], focus.freeForms[index])
    @Composable
    override fun Failure(message: String) { UiText(message, UiTextRole.Error) }
    @Composable
    override fun Submit(label: String, enabled: Boolean, onClick: () -> Unit) {
        Button(onClick, Modifier.focusRequester(focus.submit), enabled = enabled) { Text(label) }
    }
}

private class ComposeUserInputQuestion(
    private val questionId: String, private val optionFocus: FocusRequester, private val freeFormFocus: UiEditorFocusHandle,
) : UserInputQuestionRenderer {
    @Composable
    override fun Header(text: String) { UiText(text, UiTextRole.Title) }
    @Composable
    override fun Option(
        label: String, description: String, selected: Boolean, enabled: Boolean,
        autoFocus: Boolean, first: Boolean, onClick: () -> Unit,
    ) {
        OutlinedButton(
            onClick, Modifier.fillMaxWidth().then(if (first) Modifier.focusRequester(optionFocus) else Modifier),
            enabled = enabled,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text("${if (selected) "●" else "○"} $label")
                Text(description)
            }
        }
        LaunchedEffect(autoFocus, enabled) {
            if (autoFocus && enabled) { withFrameNanos { }; optionFocus.tryRequest() }
        }
    }
    @Composable
    override fun Other(selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
        OutlinedButton(onClick, enabled = enabled) { Text("${if (selected) "●" else "○"} Other") }
    }
    @Composable
    override fun FreeForm(
        text: String, enabled: Boolean, autoFocus: Boolean, onChange: (String) -> Boolean, onSubmitted: () -> Unit,
    ) {
        WithUiEditorFocus(freeFormFocus) {
            UiTextInput(text, { onChange(it) }, label = "Answer for $questionId", enabled = enabled,
                autoFocus = autoFocus, onSubmit = onSubmitted)
        }
    }
}
