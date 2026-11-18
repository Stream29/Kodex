@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.stream29.kodex.app.application.contract.TerminalOutputItem
import io.github.stream29.kodex.app.application.contract.TerminalOutputState
import io.github.stream29.kodex.cli.app.SidebarShellOutputPlacement
import io.github.stream29.kodex.cli.app.terminalOutputItemLabel
import io.github.stream29.kodex.cli.app.terminalOutputStatus
import kotlinx.coroutines.flow.StateFlow

internal class ComposeTerminalOutputPlacement(
    val bounds: Rect, val point: Offset?, val preferAfter: Boolean, val fallback: FocusRequester?,
) : SidebarShellOutputPlacement

@Composable
internal fun ComposeTerminalOutput(
    placement: ComposeTerminalOutputPlacement, command: String, output: StateFlow<TerminalOutputState>, onBack: () -> Unit,
) {
    val position = remember(placement) { PointerPopupPosition(placement.bounds, placement.preferAfter, placement.point) }
    EditorPopup(
        popupPositionProvider = position,
        onDismissRequest = onBack,
        properties = PopupProperties(focusable = true, dismissOnBackPress = false),
    ) {
        val state by output.collectAsState()
        val list = rememberLazyListState()
        val back = remember { FocusRequester() }
        val separator = rememberComposeAgentHistorySeparatorRenderer()
        var follows by remember { mutableStateOf(true) }
        var viewport by remember { mutableStateOf(1) }
        val gestures = remember(list) {
            object : NestedScrollConnection {
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    // Positive consumed Y moved away from the newest edge in this reversed list.
                    if (source == NestedScrollSource.UserInput && consumed.y > 0f) follows = false
                    return Offset.Zero
                }
            }
        }
        LaunchedEffect(state, follows) {
            if (follows && state.items.isNotEmpty()) list.requestScrollToItem(0)
        }
        NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), onBackCompleted = onBack)
        BoxWithConstraints {
            val width = (maxWidth - 12.dp).coerceIn(1.dp, 560.dp)
            val height = (maxHeight - 12.dp).coerceIn(1.dp, 420.dp)
            Surface(
                modifier = Modifier.width(width).height(height).semantics { paneTitle = "Terminal output" },
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp,
            ) {
                Column(
                    Modifier.fillMaxSize().padding(8.dp).onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Escape -> { onBack(); true }
                            Key.PageUp, Key.PageDown -> {
                                val older = event.key == Key.PageUp
                                val consumed = list.dispatchRawDelta((if (older) viewport else -viewport).toFloat())
                                if (older && consumed != 0f) follows = false
                                true
                            }
                            else -> false
                        }
                    },
                ) {
                    Text("Terminal output", style = MaterialTheme.typography.titleSmall)
                    SelectionContainer {
                        Text(command, maxLines = 2, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text(terminalOutputStatus(state.phase, state.items.isNotEmpty()), style = MaterialTheme.typography.bodySmall)
                    SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().onSizeChanged { viewport = it.height.coerceAtLeast(1) }.nestedScroll(gestures),
                            state = list,
                            reverseLayout = true,
                        ) {
                            items(state.items.size, key = { state.items[state.items.lastIndex - it].id }) { index ->
                                val item = state.items[state.items.lastIndex - index]
                                Text(
                                    terminalOutputItemLabel(item).ifEmpty { " " },
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (item is TerminalOutputItem.Text) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (!follows) separator.Separator(null, true) {
                        list.requestScrollToItem(0)
                        // The scroll affordance is going away; keep a keyboard target in this panel.
                        try { back.requestFocus() } catch (_: IllegalStateException) { }
                        follows = true
                    }
                    CompactTextButton(onBack, Modifier.focusRequester(back)) { Text("Back") }
                }
            }
        }
        LaunchedEffect(back) {
            repeat(3) {
                withFrameNanos { }
                if (try { back.requestFocus() } catch (_: IllegalStateException) { false }) return@LaunchedEffect
            }
        }
    }
}
