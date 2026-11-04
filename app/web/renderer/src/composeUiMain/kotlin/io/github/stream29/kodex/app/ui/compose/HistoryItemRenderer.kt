@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.focusable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.WorkGroupHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.ToolHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.ToolHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.ToolHistoryItemHeader
import io.github.stream29.kodex.app.history.contract.item.CommandExecutionHistoryAction
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.PatchHistoryItemState
import io.github.stream29.kodex.cli.history.*
import io.github.stream29.kodex.cli.app.ApplicationHistoryMenuPlacement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlin.time.Duration

internal enum class DisclosureFocusStage { Collapsed, Loading, Expanded }

// The item keeps focus while its header is replaced by an asynchronously loaded event.
internal class DisclosureFocusOwner(private val item: HistoryItemViewModel) {
    val requester = FocusRequester()
    var focused by mutableStateOf(false)
    var expected by mutableStateOf<DisclosureFocusStage?>(null)
    var primaryStage: DisclosureFocusStage? = null

    fun retain(stage: DisclosureFocusStage) {
        expected = stage
        if (!requester.requestFocus()) expected = null
    }

    fun currentStage(): DisclosureFocusStage? = when (item) {
        is ToolHistoryItemViewModel -> when (item.state.value) {
            is ToolHistoryItemState.Collapsed -> DisclosureFocusStage.Collapsed
            is ToolHistoryItemState.Expanding -> DisclosureFocusStage.Loading
            is ToolHistoryItemState.Expanded -> DisclosureFocusStage.Expanded
            else -> null
        }
        is PatchHistoryItemViewModel -> when (item.state.value) {
            is PatchHistoryItemState.Collapsed -> DisclosureFocusStage.Collapsed
            is PatchHistoryItemState.Expanding -> DisclosureFocusStage.Loading
            is PatchHistoryItemState.Expanded -> DisclosureFocusStage.Expanded
            else -> null
        }
        else -> null
    }
}

internal val LocalDisclosureFocusOwner = staticCompositionLocalOf<DisclosureFocusOwner?> { null }
private val LocalCollapsedCommand = staticCompositionLocalOf { false }

@Composable
internal fun HistoryDisclosureButton(
    onClick: () -> Unit, modifier: Modifier, stage: DisclosureFocusStage?,
    content: @Composable RowScope.() -> Unit,
) {
    val owner = LocalDisclosureFocusOwner.current.takeIf { stage != null }
    if (owner == null) {
        CompactTextButton(onClick, modifier, content = content)
        return
    }
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(owner, stage, owner.focused, owner.expected) {
        if (owner.focused && owner.expected == stage) {
            owner.expected = null
            requester.requestFocus()
        }
    }
    CompactTextButton(
        onClick = {
            val next = if (stage == DisclosureFocusStage.Collapsed) DisclosureFocusStage.Expanded
                else DisclosureFocusStage.Collapsed
            if (focused) owner.retain(next)
            else if (owner.focused && owner.expected != null) owner.expected = next
            onClick()
            if (owner.focused && owner.expected != null && owner.currentStage() == stage) {
                owner.expected = stage
            }
        },
        modifier = modifier.focusRequester(requester).onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) owner.primaryStage = stage
            else if (owner.primaryStage == stage) owner.primaryStage = null
        },
        content = content,
    )
}

@Composable
public actual fun rememberComposeHistoryItemRenderer(
    onOpenContextMenu: ((HistoryItemViewModel, storageIndex: Int, placement: ApplicationHistoryMenuPlacement) -> Unit)?,
): HistoryItemRenderer {
    val base = rememberComposeHistoryContentRenderer()
    val callback = rememberUpdatedState(onOpenContextMenu)
    return remember(base) { ComposeHistoryItemRenderer(base, callback) }
}

private class ComposeHistoryItemRenderer(
    base: HistoryContentRenderer, private val contextMenu: State<((HistoryItemViewModel, Int, ApplicationHistoryMenuPlacement) -> Unit)?>,
) : HistoryItemRenderer, HistoryContentRenderer by base {
    @Composable override fun Loading() { Text("") }
    @Composable override fun Error() { Text("Error", color = MaterialTheme.colorScheme.error) }
    @Composable override fun ItemHeading(value: String, elapsed: Duration, style: HistoryTextStyle) {
        rememberComposeHistoryContentRenderer(elapsed).Heading(value, style)
    }
    @Composable override fun TurnMarker(duration: Duration) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                "Worked for ${duration.roundToMilliseconds()}",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(
                Modifier.fillMaxWidth().testTag("history-turn-divider"),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
    @Composable override fun Entry(item: HistoryItemViewModel, storageIndex: Int, content: @Composable () -> Unit) {
        val origin = remember(HistoryOriginKey(item)) { ComposeMenuOrigin() }
        val disclosure = remember(HistoryOriginKey(item)) { DisclosureFocusOwner(item) }
        val toolState = (item as? ToolHistoryItemViewModel)?.state?.collectAsState()?.value
        val toolHeader = when (toolState) {
            is ToolHistoryItemState.Collapsed -> toolState.header
            is ToolHistoryItemState.Expanding -> toolState.header
            else -> null
        }
        val collapsedCommand = toolHeader is ToolHistoryItemHeader.CommandExecution &&
            toolHeader.action is CommandExecutionHistoryAction.Run
        LaunchedEffect(disclosure, item) {
            val updates: Flow<*>? = when (item) {
                is ToolHistoryItemViewModel -> item.state
                is PatchHistoryItemViewModel -> item.state
                else -> null
            }
            updates?.collect {
                val next = disclosure.currentStage()
                if (disclosure.primaryStage != null && next != null &&
                    next != disclosure.primaryStage && !disclosure.focused
                ) {
                    disclosure.retain(if (next == DisclosureFocusStage.Loading) DisclosureFocusStage.Expanded else next)
                } else if (disclosure.focused && disclosure.expected != null && next == DisclosureFocusStage.Collapsed) {
                    disclosure.expected = next
                }
            }
        }
        val entryFocus = LocalHistoryEntryFocus.current
        DisposableEffect(item, entryFocus, disclosure.requester) {
            entryFocus?.set(item, disclosure.requester)
            onDispose { if (entryFocus?.get(item) === disclosure.requester) entryFocus.remove(item) }
        }
        DisposableEffect(origin) { onDispose { origin.bounds = null } }
        Box(
            Modifier.fillMaxWidth().semantics { contentDescription = "History entry $storageIndex" }
                .onGloballyPositioned { origin.bounds = it.boundsInWindow() }
                .onPointerEvent(PointerEventType.Press, pass = PointerEventPass.Initial) { event ->
                    val open = contextMenu.value
                    if (open != null && event.buttons.isSecondaryPressed) origin.bounds?.let { bounds ->
                        // One row menu, not an additional SelectionContainer popup over it.
                        event.changes.forEach { it.consume() }
                        open(item, storageIndex, ComposeHistoryMenuPlacement(origin, bounds, event.changes.firstOrNull()?.position))
                    }
                }
                .onPreviewKeyEvent {
                    if (contextMenu.value != null && it.type == KeyEventType.KeyDown &&
                        (it.key == Key.Menu || it.key == Key.F10 && it.isShiftPressed)
                    ) {
                        origin.bounds?.let { bounds ->
                            contextMenu.value?.invoke(item, storageIndex, ComposeHistoryMenuPlacement(origin, bounds, null))
                        }
                        true
                    } else false
                }
                .onKeyEvent { event ->
                    if (disclosure.focused && disclosure.expected != null &&
                        (event.key == Key.Spacebar || event.key == Key.Enter)
                    ) {
                        if (event.type == KeyEventType.KeyUp) {
                            when (item) {
                                is ToolHistoryItemViewModel -> if (item.state.value is ToolHistoryItemState.Expanding ||
                                    item.state.value is ToolHistoryItemState.Expanded) {
                                    disclosure.expected = DisclosureFocusStage.Collapsed
                                    item.collapse()
                                }
                                is PatchHistoryItemViewModel -> if (item.state.value is PatchHistoryItemState.Expanding ||
                                    item.state.value is PatchHistoryItemState.Expanded) {
                                    disclosure.expected = DisclosureFocusStage.Collapsed
                                    item.collapse()
                                }
                                else -> Unit
                            }
                        }
                        true
                    } else false
                }
                .focusRequester(disclosure.requester)
                .onFocusChanged {
                    val wasFocused = disclosure.focused
                    disclosure.focused = it.isFocused
                    if (wasFocused && !it.isFocused) disclosure.expected = null
                }
                .focusable(),
        ) {
            CompositionLocalProvider(
                LocalDisclosureFocusOwner provides disclosure,
                LocalHistoryPatchTitle provides (item is PatchHistoryItemViewModel),
                LocalCollapsedCommand provides collapsedCommand,
            ) { content() }
        }
    }
    @Composable override fun GroupHeading(
        group: WorkGroupHistoryItemViewModel, value: String, elapsed: Duration,
        disclosure: HistoryDisclosureState, onClick: () -> Unit,
    ) {
        CompactTextButton(
            onClick, Modifier.fillMaxWidth().semantics {
                contentDescription = "History group: $value"
                stateDescription = disclosure.name
            },
        ) {
            HistoryDisclosureIndicator(disclosure)
            Box(Modifier.weight(1f)) { ItemHeading(value, elapsed, HistoryTextStyle.Normal) }
        }
    }
    @Composable override fun Collapsed(
        summary: String, status: String, elapsed: Duration, disclosure: HistoryDisclosureState, onClick: () -> Unit,
    ) {
        HistoryDisclosureButton(
            onClick, Modifier.fillMaxWidth().semantics {
                contentDescription = "Expand History item: $summary"
                stateDescription = "$status; ${disclosure.name}"
            },
            stage = if (disclosure == HistoryDisclosureState.Loading) DisclosureFocusStage.Loading
                else DisclosureFocusStage.Collapsed,
        ) {
            HistoryToolDisclosureMark(disclosure)
            Text(
                summary, Modifier.weight(1f),
                style = if (LocalHistoryPatchTitle.current) historyPatchTitleStyle() else historyToolTitleStyle(),
                maxLines = if (LocalCollapsedCommand.current) 1 else Int.MAX_VALUE,
                overflow = if (LocalCollapsedCommand.current) TextOverflow.Ellipsis else TextOverflow.Clip,
                color = when (status) {
                    "failed" -> MaterialTheme.colorScheme.error
                    "running", "streaming", "starting", "in_progress", "inprogress" -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            Text(elapsed.historyElapsedSuffix(), style = MaterialTheme.typography.bodySmall)
        }
    }
    @Composable override fun Event(
        event: StableCleanEvent, elapsed: Duration, onCollapse: (() -> Unit)?, shellSessions: AgentShellSessionRegistry?,
    ) {
        HistoryEventContent(
            event, rememberComposeHistoryContentRenderer(elapsed, onCollapse?.let { true }, onCollapse),
            shellSessions, elapsed,
        )
    }
}

private class HistoryOriginKey(private val item: HistoryItemViewModel) {
    override fun equals(other: Any?): Boolean = other is HistoryOriginKey && other.item === item
    override fun hashCode(): Int = 0
}
