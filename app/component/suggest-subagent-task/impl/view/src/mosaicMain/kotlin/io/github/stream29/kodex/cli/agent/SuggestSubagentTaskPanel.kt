package io.github.stream29.kodex.cli.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.clipToBounds
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Layout
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.unit.Constraints
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskState
import io.github.stream29.kodex.app.agent.contract.SuggestSubagentTaskViewModel
import io.github.stream29.kodex.app.agent.contract.SuggestedSessionConfiguration
import io.github.stream29.kodex.cli.components.InteractionFreeForm
import io.github.stream29.kodex.cli.components.InteractionOption
import io.github.stream29.kodex.cli.components.ScrollState
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.components.TuiDropdownMenu
import io.github.stream29.kodex.cli.components.TuiDropdownState
import io.github.stream29.kodex.cli.components.TuiDropdownTrigger
import io.github.stream29.kodex.cli.components.TuiPopupMenuItem
import io.github.stream29.kodex.cli.components.TuiPopupSubmenuItem
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.rememberTuiDropdownState
import io.github.stream29.kodex.cli.components.verticalScroll
import io.github.stream29.kodex.cli.components.wrapToTerminalWidth
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.openai.availableServiceTiers
import io.github.stream29.kodex.utils.terminaltext.takeLastFittingTerminalWidth
import io.github.stream29.kodex.utils.terminaltext.terminalCellWidth
import kotlinx.coroutines.launch
import kotlinx.io.files.Path

/**
 * Renderer-only anchors/menu state. Share this handle between the panel and menus rendered
 * directly in the host's TuiPopupHost; it contains no configuration or rejection draft.
 */
@Stable
public class SuggestSubagentTaskDropdowns private constructor(
    public val model: TuiDropdownState,
    public val requestUserInputMode: TuiDropdownState,
) {
    public companion object {
        /** Exact owner/call key disposes old menu anchors when the pending call changes. */
        @Composable
        public fun remember(owner: Any?, callId: String?): SuggestSubagentTaskDropdowns =
            key(owner, callId) {
                val model = rememberTuiDropdownState()
                val mode = rememberTuiDropdownState()
                remember(model, mode) { SuggestSubagentTaskDropdowns(model, mode) }
            }
    }
}

/**
 * Full confirmation/configuration panel. No arbitrary configuration slot or renderer-owned
 * rejection mode exists. [onBrowseWorkingDirectory] opens a host-routed Working Directory
 * child bound to its exact call; selection edits the component through updateWorkingDirectory.
 * Removing this renderer cancels only its waits/focus work, not the Agent-owned component.
 */
@Composable
public fun SuggestSubagentTaskPanel(
    viewModel: SuggestSubagentTaskViewModel,
    state: SuggestSubagentTaskState.Pending,
    columns: Int,
    rows: Int,
    dropdowns: SuggestSubagentTaskDropdowns,
    onBrowseWorkingDirectory: (String) -> Unit,
) {
    if (rows <= 0) return
    val scope = rememberCoroutineScope()
    val scrollState = remember(state.callId) { ScrollState() }
    var focusFeedback by remember(state.callId) { mutableStateOf(false) }
    val feedbackFocusRequester = remember(state.callId) { FocusRequester() }
    SideEffect {
        if (focusFeedback && feedbackFocusRequester.requestFocus()) focusFeedback = false
    }
    LaunchedEffect(focusFeedback) {
        if (!focusFeedback) return@LaunchedEffect
        repeat(3) {
            withFrameNanos { }
            if (feedbackFocusRequester.requestFocus()) {
                focusFeedback = false
                return@LaunchedEffect
            }
        }
    }
    fun submit(accepted: Boolean) {
        scope.launch { viewModel.submit(state.callId, state.revision, accepted) }
    }
    Box(modifier = Modifier.width(columns.coerceAtLeast(1)).height(rows)) {
        Column(
            modifier = Modifier.width(columns.coerceAtLeast(1)).height(rows)
                .verticalScroll(scrollState),
        ) {
            Text("Suggested Sessions", textStyle = TextStyle.Bold)
            state.arguments.tasks.forEach { task ->
                Text(
                    task.name.wrapToTerminalWidth(columns.coerceAtLeast(1)).joinToString("\n"),
                    textStyle = TextStyle.Bold,
                )
                Text(task.prompt.wrapToTerminalWidth(columns.coerceAtLeast(1)).joinToString("\n"))
            }
            SuggestedConfigurationTriggers(
                configuration = state.configuration,
                columns = columns,
                dropdowns = dropdowns,
                enabled = !state.submitting,
                onBrowse = { onBrowseWorkingDirectory(state.callId) },
            )
            listOf(true, false).forEach { accepted ->
                InteractionOption(
                    label = if (accepted) "Accept" else "Reject",
                    description = if (accepted) "Create the suggested Sessions."
                        else "Decline, optionally with a message.",
                    columns = columns,
                    selected = !accepted && state.rejecting,
                    enabled = !state.submitting,
                    onClick = {
                        if (accepted) submit(true)
                        else if (viewModel.setRejecting(state.callId, true)) focusFeedback = true
                    },
                )
            }
            if (state.rejecting) {
                InteractionFreeForm(
                    ownerKey = state.callId,
                    inputId = "rejection",
                    text = state.feedback,
                    columns = columns,
                    autoFocus = false,
                    focusRequester = feedbackFocusRequester,
                    focusOnPlacement = focusFeedback,
                    enabled = !state.submitting,
                    allowEmpty = true,
                    onValueChanged = { viewModel.updateFeedback(state.callId, it) },
                    onSubmitted = { submit(false) },
                    onFocusRequested = { focusFeedback = false },
                )
                TuiButton(
                    label = "Submit rejection",
                    enabled = !state.submitting,
                    onClick = { submit(false) },
                )
            }
        }
    }
}

/**
 * Complete typed configuration menus, rendered after persistent content directly in the
 * surrounding TuiPopupHost. Every callback edits the exact call and latest configuration;
 * stale/submitting callbacks are rejected by the component. Models are observed read-only.
 */
@Composable
public fun BoxScope.SuggestSubagentTaskConfigurationMenus(
    viewModel: SuggestSubagentTaskViewModel,
    state: SuggestSubagentTaskState.Pending,
    dropdowns: SuggestSubagentTaskDropdowns,
) {
    val models by viewModel.models.collectAsState()
    val configuration = state.configuration
    val background = TuiTheme.colorScheme.surfaceContainer
    TuiDropdownMenu(dropdownState = dropdowns.model, backgroundColor = background) {
        suggestionModelOptions(models, configuration).forEach { model ->
            val info = models.firstOrNull { it.slug == model }
            val efforts = info?.supportedReasoningLevels?.map { it.effort }.orEmpty()
                .ifEmpty { listOf(configuration.reasoningEffort) }
            val tiers = info?.availableServiceTiers().orEmpty().ifEmpty { listOf(ServiceTier.Default) }
            TuiPopupSubmenuItem(
                key = model,
                selected = model == configuration.model,
                enabled = !state.submitting,
                initialSubmenuFocusedKey = configuration.reasoningEffort
                    .takeIf { model == configuration.model && it in efforts } ?: efforts.first(),
                backgroundColor = background,
                submenuContent = {
                    efforts.forEach { effort ->
                        TuiPopupSubmenuItem(
                            key = effort,
                            selected = model == configuration.model &&
                                effort == configuration.reasoningEffort,
                            enabled = !state.submitting,
                            initialSubmenuFocusedKey = configuration.serviceTier.takeIf {
                                model == configuration.model &&
                                    effort == configuration.reasoningEffort && it in tiers
                            } ?: ServiceTier.Default,
                            backgroundColor = background,
                            submenuContent = {
                                tiers.forEach { tier ->
                                    TuiPopupMenuItem(
                                        key = tier,
                                        selected = model == configuration.model &&
                                            effort == configuration.reasoningEffort &&
                                            tier == configuration.serviceTier,
                                        enabled = !state.submitting,
                                        onClick = {
                                            viewModel.updateModelConfiguration(
                                                state.callId, model, effort, tier,
                                            )
                                        },
                                    ) { Text(tier.displayName()) }
                                }
                            },
                        ) { Text(effort.displayName()) }
                    }
                },
            ) { Text(model.value) }
        }
    }
    TuiDropdownMenu(
        dropdownState = dropdowns.requestUserInputMode,
        options = RequestUserInputMode.entries.toList(),
        selected = configuration.requestUserInputMode,
        optionLabel = RequestUserInputMode::displayName,
        enabled = !state.submitting,
        backgroundColor = background,
        onSelect = { viewModel.updateRequestUserInputMode(state.callId, it) },
    )
}

@Composable
private fun SuggestedConfigurationTriggers(
    configuration: SuggestedSessionConfiguration,
    columns: Int,
    dropdowns: SuggestSubagentTaskDropdowns,
    enabled: Boolean,
    onBrowse: () -> Unit,
) {
    val colors = TuiTheme.colorScheme
    val width = (columns - 1).coerceAtLeast(1)
    Layout(
        content = {
            TuiDropdownTrigger(
                dropdownState = dropdowns.model,
                label = suggestionConfigurationLabel(configuration),
                modifier = Modifier.background(colors.primaryContainer),
                color = colors.onPrimaryContainer,
                enabled = enabled,
            )
            TuiDropdownTrigger(
                dropdownState = dropdowns.requestUserInputMode,
                label = configuration.requestUserInputMode.displayName(),
                modifier = Modifier.background(colors.primaryContainer),
                color = colors.onPrimaryContainer,
                enabled = enabled,
            )
            TuiButton(
                label = suggestionWorkingDirectoryLabel(configuration.cwd, columns),
                modifier = Modifier.background(colors.primaryContainer),
                color = colors.onPrimaryContainer,
                enabled = enabled,
                onClick = onBrowse,
            )
        },
        modifier = Modifier.width(width).clipToBounds(),
    ) { measurables, _ ->
        val children = measurables.map {
            it.measure(Constraints(0, Constraints.Infinity, 0, 1))
        }
        var x = 0
        var y = 0
        val positions = children.map { child ->
            var nextX = if (x == 0) 0 else x + 1
            while (nextX + child.width > width && !(y > 0 && x == 0)) {
                x = 0
                y++
                nextX = 0
            }
            x = nextX
            (x to y).also { x += child.width }
        }
        layout(width, y + 1) {
            children.zip(positions).forEach { (child, position) ->
                child.place(position.first, position.second)
            }
        }
    }
}

internal fun suggestionModelOptions(
    models: List<ModelInfo>,
    configuration: SuggestedSessionConfiguration,
): List<OpenAiModelId> = (models.map(ModelInfo::slug) + configuration.model).distinct()

internal fun suggestionConfigurationLabel(configuration: SuggestedSessionConfiguration): String =
    buildString {
        append(configuration.model.value)
        append(' ')
        append(configuration.reasoningEffort.displayName())
        if (configuration.serviceTier != ServiceTier.Default) {
            append(' ')
            append(configuration.serviceTier.displayName())
        }
    }

internal fun suggestionWorkingDirectoryLabel(directory: Path, columns: Int): String {
    if (columns < 72) return "cwd"
    val path = directory.toString()
    val maximum = if (columns >= 112) 28 else 16
    return if (path.terminalCellWidth() <= maximum) path
    else "…" + path.takeLastFittingTerminalWidth(maximum - 1)
}

private fun ReasoningEffort.displayName(): String = when (this) {
    ReasoningEffort.None -> "none"
    ReasoningEffort.Minimal -> "minimal"
    ReasoningEffort.Low -> "low"
    ReasoningEffort.Medium -> "medium"
    ReasoningEffort.High -> "high"
    ReasoningEffort.XHigh -> "xhigh"
    ReasoningEffort.Max -> "max"
    is ReasoningEffort.Custom -> wireName
}

private fun ServiceTier.displayName(): String = when (this) {
    ServiceTier.Default -> "default"
    ServiceTier.Fast -> "fast"
    ServiceTier.Flex -> "flex"
}

private fun RequestUserInputMode.displayName(): String = when (this) {
    RequestUserInputMode.AskUser -> "ask user"
    RequestUserInputMode.NoQuestion -> "no question"
}
