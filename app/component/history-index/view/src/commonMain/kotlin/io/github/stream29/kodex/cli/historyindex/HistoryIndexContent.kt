package io.github.stream29.kodex.cli.historyindex

import androidx.compose.runtime.*
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.cli.history.HistoryPlanModel
import io.github.stream29.kodex.cli.history.requestUserInputHistoryRows
import io.github.stream29.kodex.cli.components.formatPopupTimestamp
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.TimeZone
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

public actual fun HistoryIndexTarget.isCurrent(): Boolean =
    viewModel.window.value.revision == revision && viewModel.contains(generation, index)

@Composable
public actual fun HistoryIndexContent(
    viewModel: HistoryIndexViewModel, side: HistoryIndexSide, renderer: HistoryIndexRenderer,
    navigation: HistoryIndexNavigation?,
) {
    val window = viewModel.window.collectAsState().value
    val supplied = navigation ?: LocalHistoryIndexNavigation.current
    val currentNavigation = rememberUpdatedState(supplied)
    val owned = supplied?.takeIf { it.owner === viewModel }
    val hasParent = owned?.returnToParent != null
    val hasCompletion = owned?.let { it.returnToParent != null && it.hasCompletionAnchor() } == true
    var mounted by remember(viewModel) { mutableStateOf(false) }
    DisposableEffect(viewModel) {
        mounted = true
        onDispose { mounted = false }
    }
    renderer.List(viewModel, window, parentRowCount = if (hasCompletion) 2 else if (hasParent) 1 else 0) { list ->
        fun addParentAction(anchor: HistoryIndexParentAnchor) {
            if (owned == null) return
            list.ParentRow(anchor) {
                renderer.ParentAction(anchor) {
                    if (mounted && currentNavigation.value === owned && viewModel.isActive &&
                        (anchor != HistoryIndexParentAnchor.Completion || owned.hasCompletionAnchor())
                    ) {
                        owned.returnToParent?.invoke(anchor)
                    }
                }
            }
        }
        if (hasParent) addParentAction(HistoryIndexParentAnchor.Creation)
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
                val childId = entry.createdChildAgentId
                renderer.Row(
                    interaction, row,
                    onHoverChanged = { hovered -> if (!hovered || target.isCurrent()) interaction.emitHover(hovered) },
                    onOpenMenu = { if (target.isCurrent()) interaction.emitMenu() },
                    onEnterChild = if (owned != null && childId != null) {
                        {
                            if (mounted && currentNavigation.value === owned && interaction.isPlaced &&
                                target.isCurrent() &&
                                viewModel.window.value.entryAtOrNull(index)?.createdChildAgentId == childId
                            ) {
                                owned.enterChild(target, childId)
                            }
                        }
                    } else null,
                )
            }
        }
        if (hasCompletion) addParentAction(HistoryIndexParentAnchor.Completion)
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
    renderer.Hover(current, historyIndexDetailPresentation(entry.detail), popupTimestamp(entry.timestamp), onHoverChanged)
}

@Composable
public actual fun HistoryIndexMenuContent(
    opening: HistoryIndexMenuOpening?, renderer: HistoryIndexRenderer,
    canEdit: Boolean, admit: () -> Boolean, edit: (HistoryIndexCommand, HistoryIndexTarget, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val current = opening ?: return
    val origin = current.interaction
    val target = origin.target
    val window = target.viewModel.window.collectAsState().value
    val entry = window.entryAtOrNull(target.index)
    val valid = window.revision == target.revision && target.isCurrent() && origin.isPlaced && entry != null
    LaunchedEffect(current, valid) { if (!valid) onDismiss() }
    if (!valid) return
    val currentAdmission by rememberUpdatedState(admit)
    val currentEdit by rememberUpdatedState(edit)
    val editable by rememberUpdatedState(canEdit)
    var mounted by remember(current) { mutableStateOf(false) }
    DisposableEffect(current) {
        mounted = true
        onDispose { mounted = false }
    }
    fun action(command: HistoryIndexCommand, label: String, text: String? = null) =
        HistoryIndexMenuAction(command.name, label) callback@{
            if (!mounted || !origin.isPlaced || !target.isCurrent() || !currentAdmission()) return@callback
            if (command != HistoryIndexCommand.GoTo && !editable) return@callback
            if (command == HistoryIndexCommand.RevertAndEdit &&
                target.viewModel.window.value.entryAtOrNull(target.index)?.editableUserText != text
            ) return@callback
            if (command == HistoryIndexCommand.GoTo) {
                onDismiss()
                target.viewModel.checkOut(target.generation, target.index)
            } else currentEdit(command, target, text)
        }
    val actions = buildList {
        add(action(HistoryIndexCommand.GoTo, "Go to"))
        if (canEdit && target.index < Int.MAX_VALUE) {
            add(action(HistoryIndexCommand.Revert, "Revert to here"))
            add(action(HistoryIndexCommand.Fork, "Fork from here"))
            entry.editableUserText?.takeIf { target.index > 0 }?.let {
                add(action(HistoryIndexCommand.RevertAndEdit, "Revert and edit", it))
            }
        }
    }
    renderer.Menu(current, popupTimestamp(entry.timestamp), actions, onDismiss)
}

@Composable
private fun popupTimestamp(value: Instant?): String? = remember(value) {
    value?.let {
        try { formatPopupTimestamp(it, TimeZone.currentSystemDefault()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
}

public actual fun historyIndexRowPresentation(
    entry: HistoryIndexEntry, position: HistoryIndexRowPosition,
): HistoryIndexRowPresentation =
    HistoryIndexRowPresentation(entry.summary, position)

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
        detail.kind.displayName, if (questions == null) detail.content else "", questions, plan,
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
        HistoryIndexEntryKind.PlanUpdate -> "Plan update"
    }

internal fun historyIndexRowPosition(position: Int, size: Int): HistoryIndexRowPosition = when {
    size <= 1 -> HistoryIndexRowPosition.Single
    position == 0 -> HistoryIndexRowPosition.First
    position == size - 1 -> HistoryIndexRowPosition.Last
    else -> HistoryIndexRowPosition.Middle
}
