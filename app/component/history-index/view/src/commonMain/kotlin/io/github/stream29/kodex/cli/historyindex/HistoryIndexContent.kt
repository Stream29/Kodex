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
            LaunchedEffect(target) {
                viewModel.onRowVisible(target.generation, target.index)
            }
            DisposableEffect(interaction) {
                onDispose {
                    interaction.emitHover(false)
                }
            }
            val position = window.indexes.binarySearch(index)
            if (position >= 0) {
                val entry = window.entries[position]
                val row = historyIndexRowPresentation(entry, historyIndexRowPosition(position, window.entries.size))
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
    val entry = window.entryAtOrNull(target.index)
    val valid = window.revision == target.revision && target.isCurrent() && current.isPlaced && entry != null
    LaunchedEffect(current, valid) { if (!valid) onDismiss() }
    if (!valid || !renderer.canShowHover) return
    var delayed by remember(current) { mutableStateOf(false) }
    LaunchedEffect(current) {
        delay(300.milliseconds)
        delayed = true
    }
    if (!delayed) return
    renderer.Hover(current, historyIndexDetailPresentation(entry.detail), onHoverChanged)
}

@Composable
public actual fun HistoryIndexMenuContent(
    opening: HistoryIndexMenuOpening?, renderer: HistoryIndexRenderer, onDismiss: () -> Unit,
) {
    val current = opening ?: return
    val origin = current.interaction
    val target = origin.target
    val window = target.viewModel.window.collectAsState().value
    val entry = window.entryAtOrNull(target.index)
    val valid = window.revision == target.revision && target.isCurrent() && origin.isPlaced && entry != null
    LaunchedEffect(current, valid) { if (!valid) onDismiss() }
    if (!valid) return
    renderer.Menu(current, entry.messageTimestamp, historyIndexDetailPresentation(entry.detail), onDismiss) {
        if (origin.isPlaced && target.isCurrent()) {
            target.viewModel.checkOut(target.generation, target.index)
        }
    }
}

public actual fun historyIndexRowPresentation(
    entry: HistoryIndexEntry, position: HistoryIndexRowPosition,
): HistoryIndexRowPresentation =
    HistoryIndexRowPresentation(HistoryIndexRowValue.Ready(entry.summary), position)

public actual fun historyIndexDetailPresentation(
    detail: HistoryIndexEntryDetail,
): HistoryIndexDetailPresentation {
    val questions = detail.requestUserInput?.requestUserInputHistoryRows()
    val plan = detail.plan?.let { event ->
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
    return HistoryIndexDetailPresentation(
        detail.kind.displayName, if (questions == null) detail.content else "", questions, false, plan,
    )
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
