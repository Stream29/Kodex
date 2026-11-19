package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.agent.contract.HistoryIndexViewModel
import io.github.stream29.kodex.app.agent.contract.HistoryIndexWindow
import io.github.stream29.kodex.cli.historyindex.*
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

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

private interface ComposeIndexPlacement : HistoryIndexInteraction, ComposeSidebarPopupPlacement

private class ComposeIndexRowOrigin(
    override val target: HistoryIndexTarget,
    side: HistoryIndexSide,
    fallback: FocusRequester?,
    private val hover: State<(HistoryIndexInteraction, Boolean) -> Unit>,
    private val menu: State<(HistoryIndexMenuOpening) -> Unit>,
) : ComposeSidebarRowOrigin(side == HistoryIndexSide.Left, fallback), ComposeIndexPlacement, HistoryIndexRowInteraction {
    override fun emitHover(hovered: Boolean) { hover.value(this, hovered) }
    override fun emitMenu() {
        if (isPlaced && target.isCurrent()) menu.value(HistoryIndexMenuOpening(ComposeIndexMenuOrigin(this, openingPoint)))
    }
}

private class ComposeIndexMenuOrigin(
    private val row: ComposeIndexRowOrigin, override val point: Offset?,
) : ComposeIndexPlacement {
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
        interaction: HistoryIndexInteraction, presentation: HistoryIndexDetailPresentation, onHoverChanged: (Boolean) -> Unit,
    ) {
        SidebarHoverPopup(interaction as ComposeIndexPlacement, presentation.title, onHoverChanged) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                HistoryIndexDetailBody(presentation, LocalTextStyle.current)
            }
        }
    }

    @Composable override fun Menu(
        opening: HistoryIndexMenuOpening, timestamp: Instant?, detail: HistoryIndexDetailPresentation?,
        onDismiss: () -> Unit, onCheckOut: () -> Unit,
    ) {
        val origin = opening.interaction as ComposeIndexPlacement
        val scroll = remember(opening) { ScrollState(0) }
        var viewport by remember(opening) { mutableIntStateOf(1) }
        val formatted = timestamp?.let {
            try {
                it.toLocalDateTime(TimeZone.currentSystemDefault()).toString()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
        }
        SidebarMenuPopup(
            opening = opening,
            origin = origin,
            restoration = restoration,
            isOriginValid = {
                origin.isPlaced && origin.target.isCurrent() && origin.target.viewModel.window.value.revision == origin.target.revision
            },
            columnModifier = Modifier.onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val direction = when (it.key) {
                    Key.PageUp -> -1
                    Key.PageDown -> 1
                    else -> return@onPreviewKeyEvent false
                }
                scroll.dispatchRawDelta((direction * viewport).toFloat())
                true
            },
            verticalArrangement = Arrangement.spacedBy(4.dp),
            actionLabel = "Check out",
            onDismiss = onDismiss,
            onAction = { onDismiss(); onCheckOut() },
        ) {
            Text("Index: ${origin.target.index}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            formatted?.let { Text("Timestamp: $it", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            Column(Modifier.weight(1f, fill = false).onSizeChanged { viewport = it.height.coerceAtLeast(1) }.verticalScroll(scroll)) {
                if (detail == null) Text("Loading history entry…", style = MaterialTheme.typography.bodySmall)
                else {
                    Text(detail.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    HistoryIndexDetailBody(detail, MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun HistoryIndexDetailBody(detail: HistoryIndexDetailPresentation, contentStyle: TextStyle) {
    val questions = detail.questionRows
    val plan = detail.plan
    when {
        questions != null -> {
            val renderer = rememberComposeHistoryFormRenderer()
            questions.forEach { renderer.Row(it) }
        }
        plan != null -> ComposeHistoryPlanBody(plan)
        else -> SelectionContainer { Text(detail.content, style = contentStyle) }
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
