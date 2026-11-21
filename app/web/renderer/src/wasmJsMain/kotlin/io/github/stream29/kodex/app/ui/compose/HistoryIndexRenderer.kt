package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.agent.contract.HistoryIndexViewModel
import io.github.stream29.kodex.app.agent.contract.HistoryIndexWindow
import io.github.stream29.kodex.cli.historyindex.*
import kotlinx.coroutines.launch

@Composable
internal fun rememberComposeHistoryIndexRenderer(
    onHoverChanged: (HistoryIndexInteraction, Boolean) -> Unit = { _, _ -> },
    onOpenMenu: (HistoryIndexMenuOpening) -> Unit = {},
): HistoryIndexRenderer {
    val hover = rememberUpdatedState(onHoverChanged)
    val menu = rememberUpdatedState(onOpenMenu)
    val scope = rememberCoroutineScope()
    return remember(scope) { ComposeHistoryIndexRenderer(hover, menu, RowMenuFocusRestoration(scope)) }
}

private class ComposeIndexRowOrigin(
    override val target: HistoryIndexTarget,
    side: HistoryIndexSide,
    fallback: FocusRequester?,
    private val hover: State<(HistoryIndexInteraction, Boolean) -> Unit>,
    private val menu: State<(HistoryIndexMenuOpening) -> Unit>,
) : ComposeSidebarRowOrigin(side == HistoryIndexSide.Left, fallback), HistoryIndexRowInteraction {
    override fun emitHover(hovered: Boolean) { hover.value(this, hovered) }
    override fun emitMenu() {
        if (isPlaced && target.isCurrent()) menu.value(HistoryIndexMenuOpening(ComposeIndexMenuOrigin(this, openingPoint)))
    }
}

private class ComposeIndexMenuOrigin(
    private val row: ComposeIndexRowOrigin, override val point: Offset?,
) : HistoryIndexInteraction, ComposeSidebarMenuPlacement {
    override val target: HistoryIndexTarget get() = row.target
    override val preferAfter: Boolean get() = row.preferAfter
    override val bounds: Rect? get() = row.bounds
    override val isPlaced: Boolean get() = row.isPlaced
    override val fallback: FocusRequester? get() = row.fallback
}

private val LocalIndexRowFocused = staticCompositionLocalOf<(() -> Unit)?> { null }

private class ComposeHistoryIndexRenderer(
    private val hover: State<(HistoryIndexInteraction, Boolean) -> Unit>,
    private val menu: State<(HistoryIndexMenuOpening) -> Unit>,
    private val restoration: RowMenuFocusRestoration,
) : HistoryIndexRenderer {
    override val canShowHover: Boolean get() = true

    @Composable override fun List(
        viewModel: HistoryIndexViewModel, window: HistoryIndexWindow, parentRowCount: Int, content: (HistoryIndexListScope) -> Unit,
    ) {
        val list = rememberLazyListState()
        val scope = rememberCoroutineScope()
        var follows by remember(viewModel) { mutableStateOf(true) }
        LaunchedEffect(viewModel, list) {
            snapshotFlow { list.canScrollForward }.collect { if (!it) follows = true }
        }
        LaunchedEffect(viewModel, window.generation, window.revision, window.entries.lastOrNull()?.index, parentRowCount, follows) {
            val last = window.entries.lastIndex + parentRowCount
            if (follows && last >= 0) list.requestScrollToItem(last)
        }
        val gesture = remember(viewModel, list) {
            object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && consumed.y != 0f) follows = !list.canScrollForward
                    return Offset.Zero
                }
            }
        }
        CompositionLocalProvider(LocalIndexRowFocused provides {
            follows = false
            scope.launch {
                withFrameNanos { }
                follows = !list.canScrollForward
            }
        }) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().nestedScroll(gesture)) {
                content(ComposeHistoryIndexListScope(this))
            }
        }
    }

    @Composable override fun ParentAction(anchor: HistoryIndexParentAnchor, onReturn: () -> Unit) {
        CompactTextButton(onClick = onReturn, modifier = Modifier.fillMaxWidth()) {
            Text(anchor.label, modifier = Modifier.weight(1f))
        }
    }

    @Composable override fun rememberInteraction(target: HistoryIndexTarget, side: HistoryIndexSide): HistoryIndexRowInteraction {
        val fallback = LocalRowMenuFallback.current
        return remember(target, side, fallback) { ComposeIndexRowOrigin(target, side, fallback, hover, menu) }
    }

    @Composable override fun Row(
        interaction: HistoryIndexRowInteraction, presentation: HistoryIndexRowPresentation,
        onHoverChanged: (Boolean) -> Unit, onOpenMenu: () -> Unit, onEnterChild: (() -> Unit)?,
    ) {
        val origin = interaction as ComposeIndexRowOrigin
        Row(
            Modifier.fillMaxWidth().onGloballyPositioned { origin.hoverBounds = it.boundsInWindow() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                SidebarPopupRow(
                    origin = origin,
                    summary = presentation.summary,
                    rowDescription = "History index ${origin.target.index}",
                    rowStateDescription = "Read only",
                    menuActionDescription = "Open menu for history index ${origin.target.index}",
                    boldOnHover = true,
                    onFocused = LocalIndexRowFocused.current,
                    onHoverChanged = onHoverChanged,
                    onOpenMenu = onOpenMenu,
                )
            }
            if (onEnterChild != null) {
                CompactTextButton(
                    onClick = onEnterChild,
                    modifier = Modifier.semantics { contentDescription = "Enter subagent" },
                ) { Text("Enter", maxLines = 1) }
            }
        }
    }

    @Composable override fun Hover(
        interaction: HistoryIndexInteraction, presentation: HistoryIndexDetailPresentation, timestamp: String?,
        onHoverChanged: (Boolean) -> Unit,
    ) {
        val scroll = key(interaction) { rememberScrollState() }
        SidebarHoverPopup(interaction as ComposeIndexRowOrigin, presentation.title, onHoverChanged) {
            HistoryIndexMetadata(interaction.target.index, timestamp)
            Column(Modifier.weight(1f, fill = false).verticalScroll(scroll)) {
                HistoryIndexDetailBody(presentation)
            }
        }
    }

    @Composable override fun Menu(
        opening: HistoryIndexMenuOpening, timestamp: String?, actions: List<HistoryIndexMenuAction>, onDismiss: () -> Unit,
    ) {
        val origin = opening.interaction as ComposeSidebarMenuPlacement
        val target = opening.interaction.target
        val goTo = actions.first()
        SidebarMenuPopup(
            opening = opening,
            origin = origin,
            restoration = restoration,
            isOriginValid = {
                origin.isPlaced && target.isCurrent() && target.viewModel.window.value.revision == target.revision
            },
            actionLabel = goTo.label,
            onDismiss = onDismiss,
            onAction = goTo.onClick,
            afterAction = {
                actions.drop(1).forEach { action ->
                    key(action.key) {
                        CompactTextButton(onClick = action.onClick, modifier = Modifier.fillMaxWidth()) {
                            Text(action.label, Modifier.weight(1f))
                        }
                    }
                }
            },
        ) {
            HistoryIndexMetadata(target.index, timestamp)
        }
    }
}

@Composable
private fun HistoryIndexMetadata(index: Int, timestamp: String?) {
    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Text("Index: $index", style = style, color = color)
        if (timestamp != null) Text("Timestamp: $timestamp", style = style, color = color)
    }
}

@Composable
private fun HistoryIndexDetailBody(detail: HistoryIndexDetailPresentation) {
    val questions = detail.questionRows
    val plan = detail.plan
    when {
        questions != null -> {
            val renderer = rememberComposeHistoryFormRenderer()
            questions.forEach { renderer.Row(it) }
        }
        plan != null -> ComposeHistoryPlanBody(plan)
        else -> SelectionContainer { Text(detail.content) }
    }
}

private class ComposeHistoryIndexListScope(private val original: androidx.compose.foundation.lazy.LazyListScope) : HistoryIndexListScope {
    override fun ParentRow(anchor: HistoryIndexParentAnchor, content: @Composable () -> Unit) {
        original.item(key = anchor.name) { content() }
    }

    override fun Rows(window: HistoryIndexWindow, content: @Composable (Int) -> Unit) {
        original.items(
            window.entries.size,
            { HistoryIndexRowKey(window.generation, window.revision, window.entries[it].index) },
        ) {
            content(window.entries[it].index)
        }
    }
}
