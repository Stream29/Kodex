package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.ui.*
import io.github.stream29.kodex.cli.agent.*
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationMenu
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationRenderer
import kotlinx.io.files.Path

@Composable
public actual fun rememberComposeSuggestionRenderer(): SuggestionRenderer {
    val runtime = rememberComposeRuntimeConfigurationRenderer()
    return remember(runtime) { ComposeSuggestionRenderer(runtime) }
}

private class ComposeSuggestionRenderer(private val runtime: RuntimeConfigurationRenderer) : SuggestionRenderer {
    @Composable
    override fun Frame(callId: String, content: @Composable (SuggestionRenderScope) -> Unit) {
        key(callId) { FrameContent(content) }
    }
    @Composable
    private fun FrameContent(content: @Composable (SuggestionRenderScope) -> Unit) {
        val requester = rememberUiEditorFocusHandle()
        val focus = remember(requester) { SuggestionFocus(requester) }
        val controls = remember(focus, runtime) { ComposeSuggestionScope(runtime, focus) }
        Column(
            Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { content(controls) }
        SideEffect { focus.applyPending() }
        LaunchedEffect(focus.pending) {
            if (focus.applyPending()) return@LaunchedEffect
            repeat(3) { withFrameNanos { }; if (focus.applyPending()) return@LaunchedEffect }
        }
    }
}

@Stable
private class SuggestionFocus(val requester: UiEditorFocusHandle) {
    var pending by mutableStateOf(false)
    fun applyPending(): Boolean {
        if (!pending) return true
        val focused = requester.requestFocus()
        if (focused) pending = false
        return focused
    }
}

private class ComposeSuggestionScope(
    private val runtime: RuntimeConfigurationRenderer, private val focus: SuggestionFocus,
) : SuggestionRenderScope {
    @Composable override fun Header(text: String) { UiText(text, UiTextRole.Title) }
    @Composable override fun Task(name: String, prompt: String) {
        Column { UiText(name, UiTextRole.Title); UiText(prompt) }
    }
    @Composable override fun Configuration(menu: RuntimeConfigurationMenu, cwd: Path, enabled: Boolean, onBrowse: () -> Unit) {
        Column(Modifier.fillMaxWidth()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                runtime.Fields(menu, enabled)
            }
            CompactTextButton(onBrowse, enabled = enabled && LocalGraphicalHostCapabilities.current.directoryBrowsing) {
                Text("Working directory: $cwd")
            }
        }
    }
    @Composable override fun Decision(
        label: String, description: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit,
    ) {
        val admitted by rememberUpdatedState(enabled)
        val invoke by rememberUpdatedState(onClick)
        Surface(
            color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
            shape = RoundedCornerShape(4.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 32.dp)
                    .semantics { this.selected = selected }
                    .clickable(enabled = enabled, role = Role.Button) { if (admitted) invoke() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(3.dp).height(20.dp).background(
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(2.dp),
                ))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Text(description, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    @Composable override fun Feedback(text: String, enabled: Boolean, onChange: (String) -> Unit, onSubmitted: () -> Unit) {
        WithUiEditorFocus(focus.requester) {
            UiTextInput(text, onChange, label = "Suggestion feedback (optional)", enabled = enabled, onSubmit = onSubmitted)
        }
    }
    @Composable override fun RejectSubmit(enabled: Boolean, onClick: () -> Unit) {
        CompactSubmitAction("Submit rejection", enabled, onClick = onClick)
    }
    override fun focusFeedback() { focus.pending = true }
}
