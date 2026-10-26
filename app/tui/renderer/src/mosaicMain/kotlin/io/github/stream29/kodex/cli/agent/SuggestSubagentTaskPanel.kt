@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.agent

import androidx.compose.runtime.*
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.*
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.*
import com.jakewharton.mosaic.ui.unit.Constraints
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskState
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskViewModel
import io.github.stream29.kodex.cli.components.*
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationMenu
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeNodes
import io.github.stream29.kodex.utils.terminaltext.takeLastFittingTerminalWidth
import io.github.stream29.kodex.utils.terminaltext.terminalCellWidth
import kotlinx.io.files.Path

@Stable
public actual class SuggestSubagentTaskDropdowns private constructor(
    public actual val model: TuiDropdownState,
    public actual val requestUserInputMode: TuiDropdownState,
) {
    public actual companion object {
        @Composable
        public actual fun remember(owner: Any?, callId: String?): SuggestSubagentTaskDropdowns = key(owner, callId) {
            val model = rememberTuiDropdownState()
            val mode = rememberTuiDropdownState()
            remember(model, mode) { SuggestSubagentTaskDropdowns(model, mode) }
        }
    }
}

@Composable
public actual fun SuggestSubagentTaskPanel(
    viewModel: SuggestSubagentTaskViewModel, state: SuggestSubagentTaskState.Pending, columns: Int, rows: Int,
    dropdowns: SuggestSubagentTaskDropdowns, onBrowseWorkingDirectory: (String) -> Unit,
) {
    if (rows <= 0) return
    val renderer = remember(columns, rows, dropdowns) { MosaicSuggestionRenderer(columns, rows, dropdowns) }
    SuggestSubagentTaskContent(viewModel, state, renderer, onBrowseWorkingDirectory)
}

@Composable
public actual fun BoxScope.SuggestSubagentTaskConfigurationMenus(
    viewModel: SuggestSubagentTaskViewModel, state: SuggestSubagentTaskState.Pending, dropdowns: SuggestSubagentTaskDropdowns,
) {
    SuggestionConfigurationMenuContent(viewModel, state) { menu ->
        val background = TuiTheme.colorScheme.surfaceContainer
        TuiDropdownMenu(dropdownState = dropdowns.model, backgroundColor = background) {
            RuntimeNodes(menu.models, background, enabled = !state.submitting)
        }
        TuiDropdownMenu(dropdownState = dropdowns.requestUserInputMode, backgroundColor = background) {
            RuntimeNodes(menu.modes, background, enabled = !state.submitting)
        }
    }
}

private class MosaicSuggestionRenderer(
    private val columns: Int, private val rows: Int, private val dropdowns: SuggestSubagentTaskDropdowns,
) : SuggestionRenderer {
    @Composable
    override fun Frame(callId: String, content: @Composable (SuggestionRenderScope) -> Unit) {
        val scroll = remember(callId) { ScrollState() }
        val focus = remember(callId) { MosaicSuggestionFocus() }
        val controls = remember(callId, columns, dropdowns, focus) { MosaicSuggestionScope(callId, columns, dropdowns, focus) }
        SideEffect { focus.applyPending() }
        LaunchedEffect(focus.pending) {
            if (focus.applyPending()) return@LaunchedEffect
            repeat(3) { withFrameNanos { }; if (focus.applyPending()) return@LaunchedEffect }
        }
        Box(Modifier.width(columns.coerceAtLeast(1)).height(rows)) {
            Column(Modifier.width(columns.coerceAtLeast(1)).height(rows).verticalScroll(scroll)) { content(controls) }
        }
    }
}

@Stable
private class MosaicSuggestionFocus {
    val requester = FocusRequester()
    var pending by mutableStateOf(false)
    fun applyPending(): Boolean {
        if (!pending) return true
        if (requester.requestFocus()) { pending = false; return true }
        return false
    }
}

private class MosaicSuggestionScope(
    private val callId: String, private val columns: Int,
    private val dropdowns: SuggestSubagentTaskDropdowns, private val focus: MosaicSuggestionFocus,
) : SuggestionRenderScope {
    @Composable override fun Header(text: String) { Text(text, textStyle = TextStyle.Bold) }
    @Composable override fun Task(name: String, prompt: String) {
        Text(name.wrapToTerminalWidth(columns.coerceAtLeast(1)).joinToString("\n"), textStyle = TextStyle.Bold)
        Text(prompt.wrapToTerminalWidth(columns.coerceAtLeast(1)).joinToString("\n"))
    }
    @Composable override fun Configuration(menu: RuntimeConfigurationMenu, cwd: Path, enabled: Boolean, onBrowse: () -> Unit) {
        SuggestedConfigurationTriggers(menu, cwd, columns, dropdowns, enabled, onBrowse)
    }
    @Composable override fun Decision(
        label: String, description: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit,
    ) { InteractionOption(label, description, columns, selected = selected, enabled = enabled, onClick = onClick) }
    @Composable override fun Feedback(text: String, enabled: Boolean, onChange: (String) -> Unit, onSubmitted: () -> Unit) {
        InteractionFreeForm(
            callId, "rejection", text, columns, autoFocus = false, focusRequester = focus.requester,
            focusOnPlacement = focus.pending, enabled = enabled, allowEmpty = true,
            onValueChanged = onChange, onSubmitted = onSubmitted, onFocusRequested = { focus.pending = false },
        )
    }
    @Composable override fun RejectSubmit(enabled: Boolean, onClick: () -> Unit) {
        TuiButton("Submit rejection", enabled = enabled, onClick = onClick)
    }
    override fun focusFeedback() { focus.pending = true }
}

@Composable
private fun SuggestedConfigurationTriggers(
    menu: RuntimeConfigurationMenu, directory: Path, columns: Int, dropdowns: SuggestSubagentTaskDropdowns,
    enabled: Boolean, onBrowse: () -> Unit,
) {
    val colors = TuiTheme.colorScheme
    val width = (columns - 1).coerceAtLeast(1)
    Layout(
        content = {
            TuiDropdownTrigger(
                dropdowns.model, menu.modelLabel, Modifier.background(colors.primaryContainer),
                color = colors.onPrimaryContainer, enabled = enabled,
            )
            TuiDropdownTrigger(
                dropdowns.requestUserInputMode, menu.modeLabel, Modifier.background(colors.primaryContainer),
                color = colors.onPrimaryContainer, enabled = enabled,
            )
            TuiButton(
                suggestionWorkingDirectoryLabel(directory, columns), Modifier.background(colors.primaryContainer),
                color = colors.onPrimaryContainer, enabled = enabled, onClick = onBrowse,
            )
        },
        modifier = Modifier.width(width).clipToBounds(),
    ) { measurables, _ ->
        val children = measurables.map { it.measure(Constraints(0, Constraints.Infinity, 0, 1)) }
        var x = 0
        var y = 0
        val positions = children.map { child ->
            var nextX = if (x == 0) 0 else x + 1
            while (nextX + child.width > width && !(y > 0 && x == 0)) {
                x = 0; y++; nextX = 0
            }
            x = nextX
            (x to y).also { x += child.width }
        }
        layout(width, y + 1) {
            children.zip(positions).forEach { (child, position) -> child.place(position.first, position.second) }
        }
    }
}

internal fun suggestionWorkingDirectoryLabel(directory: Path, columns: Int): String {
    if (columns < 72) return "cwd"
    val path = directory.toString()
    val maximum = if (columns >= 112) 28 else 16
    return if (path.terminalCellWidth() <= maximum) path else "…" + path.takeLastFittingTerminalWidth(maximum - 1)
}
