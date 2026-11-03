package io.github.stream29.kodex.cli.historyindex

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.cli.history.HistoryPlanModel
import io.github.stream29.kodex.cli.history.requestUserInputHistoryRows
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

public actual fun HistoryIndexTarget.isCurrent(): Boolean =
    viewModel.window.value.revision == revision && viewModel.contains(generation, index)

@Composable
public actual fun HistoryIndexContent(
    viewModel: HistoryIndexViewModel, side: HistoryIndexSide, renderer: HistoryIndexRenderer,
) {
    val window = viewModel.window.collectAsState().value
    renderer.List(viewModel, window) { list ->
        list.Rows(window) { index ->
            val target = remember(viewModel, window.generation, window.revision, index) {
                HistoryIndexTarget(viewModel, window.generation, index, window.revision)
            }
            val interaction = renderer.rememberInteraction(target, side)
            val handle = remember(interaction) { viewModel.acquireRow(target.generation, target.index) }
            DisposableEffect(interaction, handle) {
                onDispose {
                    handle.release()
                    interaction.emitHover(false)
                }
            }
            val state = handle.state.collectAsState().value
            historyIndexRowPresentation(state, historyIndexRowPosition(window.indexes.binarySearch(index), window.indexes.size))?.let { row ->
                renderer.Row(
                    interaction, row,
                    onHoverChanged = { hovered -> if (!hovered || target.isCurrent()) interaction.emitHover(hovered) },
                    onOpenMenu = { if (target.isCurrent()) interaction.emitMenu() },
                )
            }
        }
    }
}

@Composable
public actual fun HistoryIndexHoverContent(
    interaction: HistoryIndexInteraction?, renderer: HistoryIndexRenderer,
    onHoverChanged: (Boolean) -> Unit, onDismiss: () -> Unit,
) {
    val current = interaction ?: return
    val target = current.target
    val window = target.viewModel.window.collectAsState().value
    val valid = window.revision == target.revision && target.isCurrent() && current.isPlaced
    LaunchedEffect(current, valid) { if (!valid) onDismiss() }
    if (!valid || !renderer.canShowHover) return
    var delayed by remember(current) { mutableStateOf(false) }
    LaunchedEffect(current) {
        delay(300.milliseconds)
        delayed = true
    }
    if (!delayed) return
    val handle = remember(current) { target.viewModel.acquireDetail(target.generation, target.index) }
    DisposableEffect(handle) { onDispose { handle.release() } }
    val state = handle.state.collectAsState().value
    LaunchedEffect(current, state) { if (state == HistoryIndexReadState.Closed) onDismiss() }
    historyIndexDetailPresentation(state)?.let { renderer.Hover(current, it, onHoverChanged) }
}

@Composable
public actual fun HistoryIndexMenuContent(
    opening: HistoryIndexMenuOpening?, renderer: HistoryIndexRenderer, onDismiss: () -> Unit,
) {
    val current = opening ?: return
    val origin = current.interaction
    val target = origin.target
    val window = target.viewModel.window.collectAsState().value
    val valid = window.revision == target.revision && target.isCurrent() && origin.isPlaced
    LaunchedEffect(current, valid) { if (!valid) onDismiss() }
    if (!valid) return
    val handle = remember(current) { target.viewModel.acquireTimestamp(target.generation, target.index) }
    DisposableEffect(handle) { onDispose { handle.release() } }
    val detailHandle = remember(current) { target.viewModel.acquireDetail(target.generation, target.index) }
    DisposableEffect(detailHandle) { onDispose { detailHandle.release() } }
    val state = handle.state.collectAsState().value
    val detailState = detailHandle.state.collectAsState().value
    LaunchedEffect(current, state, detailState) {
        if (state == HistoryIndexReadState.Closed || detailState == HistoryIndexReadState.Closed) onDismiss()
    }
    if (state == HistoryIndexReadState.Closed || detailState == HistoryIndexReadState.Closed) return
    renderer.Menu(current, (state as? HistoryIndexReadState.Ready)?.value, historyIndexDetailPresentation(detailState), onDismiss) {
        if (handle.state.value != HistoryIndexReadState.Closed && detailHandle.state.value != HistoryIndexReadState.Closed &&
            origin.isPlaced && target.isCurrent()
        ) {
            target.viewModel.checkOut(target.generation, target.index)
        }
    }
}

public actual fun historyIndexRowPresentation(
    state: HistoryIndexReadState<HistoryIndexEntry>, position: HistoryIndexRowPosition,
): HistoryIndexRowPresentation? {
    val value = when (state) {
        HistoryIndexReadState.Loading -> HistoryIndexRowValue.Loading
        HistoryIndexReadState.Failed -> HistoryIndexRowValue.Failed
        HistoryIndexReadState.Closed -> return null
        is HistoryIndexReadState.Ready -> HistoryIndexRowValue.Ready(state.value.summary)
    }
    return HistoryIndexRowPresentation(value, position)
}

public actual fun historyIndexDetailPresentation(
    state: HistoryIndexReadState<HistoryIndexEntryDetail>,
): HistoryIndexDetailPresentation? = when (state) {
    HistoryIndexReadState.Loading, HistoryIndexReadState.Closed -> null
    HistoryIndexReadState.Failed -> HistoryIndexDetailPresentation(
        "Error", "Unable to read or decode the history entry.", null, true,
    )
    is HistoryIndexReadState.Ready -> {
        val questions = state.value.requestUserInput?.requestUserInputHistoryRows()
        val plan = state.value.plan?.let { event ->
            val originalSteps = event.arguments.plan
            val explanation = event.arguments.explanation?.takeIf(String::isNotBlank)?.let {
                if (originalSteps.isEmpty()) it.trim() else it.trimStart()
            }
            val steps = originalSteps.mapIndexed { index, item ->
                val step = item.step.ifBlank { "[empty]" }
                item.copy(step = if (index == originalSteps.lastIndex) step.trimEnd() else step)
            }
            HistoryPlanModel(explanation, steps, pending = false, emptyLabel = if (explanation == null) "[empty]" else null)
        }
        HistoryIndexDetailPresentation(
            state.value.kind.displayName, if (questions == null) state.value.content else "", questions, false, plan,
        )
    }
}

private val HistoryIndexEntryKind.displayName: String
    get() = when (this) {
        HistoryIndexEntryKind.CompactionPoint -> "Compaction point"
        HistoryIndexEntryKind.UserMessage -> "User message"
        HistoryIndexEntryKind.AssistantMessage -> "Assistant message"
        HistoryIndexEntryKind.AssistantCommentary -> "Assistant commentary"
        HistoryIndexEntryKind.AssistantFinal -> "Assistant final"
        HistoryIndexEntryKind.DeveloperMessage -> "Developer message"
        HistoryIndexEntryKind.AgentMessage -> "Agent message"
        HistoryIndexEntryKind.RequestUserInput -> "Request user input"
        HistoryIndexEntryKind.SuggestSubagents -> "suggest subagents"
        HistoryIndexEntryKind.PlanUpdate -> "Plan update"
    }

internal fun historyIndexRowPosition(position: Int, size: Int): HistoryIndexRowPosition = when {
    size <= 1 -> HistoryIndexRowPosition.Single
    position == 0 -> HistoryIndexRowPosition.First
    position == size - 1 -> HistoryIndexRowPosition.Last
    else -> HistoryIndexRowPosition.Middle
}
