package io.github.stream29.kodex.cli.historyindex

import androidx.compose.runtime.*
import com.jakewharton.mosaic.layout.*
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.BoxScope
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.unit.IntOffset
import io.github.stream29.kodex.app.agent.contract.HistoryIndexViewModel
import io.github.stream29.kodex.app.agent.contract.HistoryIndexWindow
import io.github.stream29.kodex.cli.app.SessionSidebarSide
import io.github.stream29.kodex.cli.app.SidebarHoverPopupSurface
import io.github.stream29.kodex.cli.components.*
import io.github.stream29.kodex.openai.StepStatus

internal open class MosaicHistoryIndexOrigin(
    val request: HistoryIndexInteractionRequest, val clickPosition: IntOffset? = null,
) : HistoryIndexInteraction {
    override val target: HistoryIndexTarget get() = request.sharedTarget
    override val isPlaced: Boolean get() = request.anchor.isPlaced
}

private class MosaicHistoryIndexRowInteraction(
    request: HistoryIndexInteractionRequest,
    private val hover: State<(HistoryIndexInteractionRequest, Boolean) -> Unit>,
    private val menu: State<(HistoryIndexMenuRequest) -> Unit>,
) : MosaicHistoryIndexOrigin(request), HistoryIndexRowInteraction {
    var openingPosition: IntOffset? = null
    override fun emitHover(hovered: Boolean) { hover.value(request, hovered) }
    override fun emitMenu() { menu.value(HistoryIndexMenuRequest(request, openingPosition)) }
}

internal class MosaicHistoryIndexRenderer(
    private val columns: Int, private val rows: Int,
    private val listState: LazyListState? = null,
    private val onHoverChanged: (HistoryIndexInteractionRequest, Boolean) -> Unit = { _, _ -> },
    private val onOpenMenu: (HistoryIndexMenuRequest) -> Unit = {},
    private val popupScope: BoxScope? = null,
) : HistoryIndexRenderer {
    override val canShowHover: Boolean get() = columns > 0 && rows > 0

    @Composable override fun List(
        viewModel: HistoryIndexViewModel, window: HistoryIndexWindow, parentRowCount: Int, content: (HistoryIndexListScope) -> Unit,
    ) {
        val list = checkNotNull(listState)
        val followsLatest = remember(viewModel) { mutableStateOf(true) }
        val interaction = remember(viewModel, list) {
            MutableScrollInteractionSource { input ->
                if (input.orientation == ScrollOrientation.Vertical &&
                    (input.source == ScrollInputSource.Pointer || input.source == ScrollInputSource.Keyboard)
                ) {
                    if (input.consumedDelta < 0) followsLatest.value = false
                    else if (!list.canScrollForward) followsLatest.value = true
                }
            }
        }
        LaunchedEffect(viewModel, list) {
            snapshotFlow { list.canScrollForward }.collect { forward -> if (!forward) followsLatest.value = true }
        }
        LaunchedEffect(viewModel, window.generation, window.revision, window.entries.lastOrNull()?.index, parentRowCount, followsLatest.value) {
            if (followsLatest.value) list.requestScrollToEnd()
        }
        LazyColumn(
            Modifier.width(columns.coerceAtLeast(0)).height(rows.coerceAtLeast(0)), state = list, interactionSource = interaction,
        ) { content(MosaicHistoryIndexListScope(this)) }
    }
    @Composable override fun ParentAction(anchor: HistoryIndexParentAnchor, onReturn: () -> Unit) {
        TuiPressable(onClick = onReturn, modifier = Modifier.fillMaxWidth()) { focused, hovered, pressed ->
            EllipsizedText(
                anchor.label, modifier = Modifier.fillMaxWidth(),
                color = TuiTheme.colorScheme.onSurface,
                textStyle = tuiInteractionTextStyle(selected = focused, hovered = hovered, pressed = pressed),
            )
        }
    }

    @Composable override fun rememberInteraction(target: HistoryIndexTarget, side: HistoryIndexSide): HistoryIndexRowInteraction {
        val anchor = rememberTuiPopupAnchor()
        val request = remember(target, side, anchor) {
            HistoryIndexInteractionRequest(side, target.viewModel, target.generation, target.index, anchor, target.revision)
        }
        val hover = rememberUpdatedState(onHoverChanged)
        val menu = rememberUpdatedState(onOpenMenu)
        return remember(request) { MosaicHistoryIndexRowInteraction(request, hover, menu) }
    }
    @Composable override fun Row(
        interaction: HistoryIndexRowInteraction, presentation: HistoryIndexRowPresentation,
        onHoverChanged: (Boolean) -> Unit, onOpenMenu: () -> Unit, onEnterChild: (() -> Unit)?,
    ) {
        val origin = interaction as MosaicHistoryIndexRowInteraction
        val request = origin.request
        // The pressable and full row share a top-left; context menus use only that point.
        Row(Modifier.fillMaxWidth().tuiPopupAnchor(request.anchor).background(TuiTheme.colorScheme.surfaceContainer)) {
            TuiPressable(
                onClick = {},
                onSecondaryClick = { position -> origin.openingPosition = position; onOpenMenu() },
                modifier = Modifier.weight(1f)
                    .onPointerHover(
                        onPointerEnter = { onHoverChanged(true) }, onPointerExit = { onHoverChanged(false) },
                    ),
            ) { _, hovered, pressed -> MosaicHistoryIndexRowText(presentation, hovered, pressed) }
            if (onEnterChild != null) TuiButton("Enter", onClick = onEnterChild)
        }
    }
    @Composable override fun Hover(
        interaction: HistoryIndexInteraction, presentation: HistoryIndexDetailPresentation, timestamp: String?,
        onHoverChanged: (Boolean) -> Unit,
    ) {
        val request = (interaction as MosaicHistoryIndexOrigin).request
        val sidebarSide = if (request.side == HistoryIndexSide.Left) SessionSidebarSide.Left else SessionSidebarSide.Right
        val content = remember(presentation) {
            val plan = presentation.plan
            if (plan == null) presentation.content else buildString {
                plan.explanation?.let { appendLine(it) }
                plan.steps.forEach { item ->
                    val marker = when (item.status) {
                        StepStatus.Pending -> "[ ]"
                        StepStatus.InProgress -> "[>]"
                        StepStatus.Completed -> "[x]"
                    }
                    appendLine("$marker ${item.step}")
                }
                if (plan.steps.isEmpty()) plan.emptyLabel?.let { append(it) }
            }.trimEnd('\n')
        }
        with(checkNotNull(popupScope)) {
            SidebarHoverPopupSurface(
                anchor = request.anchor,
                side = sidebarSide,
                title = presentation.title,
                content = content,
                contentColumns = columns,
                contentRows = rows,
                metadata = buildList {
                    add("Index: ${interaction.target.index}")
                    if (timestamp != null) add("Timestamp: $timestamp")
                },
                requestUserInputRows = presentation.questionRows,
                titleColor = TuiTheme.colorScheme.onSurface,
                onHoverChanged = onHoverChanged,
            )
        }
    }
    @Composable override fun Menu(
        opening: HistoryIndexMenuOpening, timestamp: String?, actions: List<HistoryIndexMenuAction>, onDismiss: () -> Unit,
    ) {
        val origin = opening.interaction as MosaicHistoryIndexOrigin
        val menuState = remember(opening) { TuiPopupMenuState() }
        with(checkNotNull(popupScope)) {
            HistoryIndexContextMenuPopup(
                origin.request.anchor, origin.clickPosition, origin.target.index, timestamp, actions, onDismiss, menuState,
            )
        }
    }
}

private class MosaicHistoryIndexListScope(private val original: LazyListScope) : HistoryIndexListScope {
    override fun ParentRow(anchor: HistoryIndexParentAnchor, content: @Composable () -> Unit) {
        original.item(key = anchor, content = content)
    }

    override fun Rows(window: HistoryIndexWindow, content: @Composable (Int) -> Unit) {
        original.items(
            window.entries,
            key = { HistoryIndexRowKey(window.generation, window.revision, it.index) },
        ) { entry -> content(entry.index) }
    }
}
