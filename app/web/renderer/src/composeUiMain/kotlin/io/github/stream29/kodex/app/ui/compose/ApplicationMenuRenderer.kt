package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.ui.compose.EditorDialog as Dialog
import io.github.stream29.kodex.app.ui.compose.EditorPopup as Popup
import androidx.compose.ui.window.PopupProperties
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.stream29.kodex.app.sessiontabbar.contract.SessionTabMenuPlacement
import io.github.stream29.kodex.cli.app.*

@Composable
public actual fun rememberComposeApplicationMenuRenderer(): ApplicationMenuRenderer =
    remember { ComposeApplicationMenuRenderer() }

private class ComposeApplicationMenuRenderer : ApplicationMenuRenderer {
    override fun isTabPlaced(placement: SessionTabMenuPlacement): Boolean =
        (placement as ComposeSessionTabPlacement).origin.isPlaced
    override fun isHistoryPlaced(placement: ApplicationHistoryMenuPlacement): Boolean =
        (placement as ComposeHistoryMenuPlacement).origin.isPlaced

    @Composable
    override fun Tab(placement: SessionTabMenuPlacement, entries: List<ApplicationMenuEntry>, onDismiss: () -> Unit) {
        val origin = placement as ComposeSessionTabPlacement
        ApplicationMenu(origin.origin.bounds ?: origin.bounds, origin.point, entries, onDismiss)
    }
    @Composable
    override fun History(placement: ApplicationHistoryMenuPlacement, entries: List<ApplicationMenuEntry>, onDismiss: () -> Unit) {
        val origin = placement as ComposeHistoryMenuPlacement
        ApplicationMenu(origin.origin.bounds ?: origin.bounds, origin.point, entries, onDismiss)
    }
    @Composable
    override fun RevertConfirmation(
        title: String, paragraphs: List<String>, cancelLabel: String, confirmLabel: String,
        onCancel: () -> Unit, onConfirm: () -> Unit,
    ) {
        val cancel = rememberInitialFocusModifier(autoFocus = true, enabled = true)
        Dialog(onDismissRequest = onCancel) {
            Surface(
                modifier = Modifier.semantics { paneTitle = title },
                shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    Modifier.widthIn(max = 560.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    paragraphs.forEach { Text(it) }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CompactTextButton(onCancel, cancel) { Text(cancelLabel) }
                        CompactTextButton(onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                            Text(confirmLabel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApplicationMenu(bounds: Rect, point: Offset?, entries: List<ApplicationMenuEntry>, onDismiss: () -> Unit) {
    val position = remember(bounds, point) { PointerPopupPosition(bounds, preferAfter = true, point) }
    val actionKeys = entries.filterIsInstance<ApplicationMenuEntry.Action>().map { it.key }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    requesters.keys.retainAll(actionKeys.toSet())
    actionKeys.forEach { requesters.getOrPut(it) { FocusRequester() } }
    var focusedKey by remember { mutableStateOf<String?>(null) }
    fun focusIndex(index: Int) {
        val target = actionKeys.getOrNull(index) ?: return
        if (requesters[target]?.requestApplicationMenuFocus() == true) focusedKey = target
    }
    LaunchedEffect(actionKeys) {
        if (focusedKey in actionKeys) return@LaunchedEffect
        repeat(3) {
            withFrameNanos { }
            val first = actionKeys.firstOrNull()
            if (first != null && requesters[first]?.requestApplicationMenuFocus() == true) {
                focusedKey = first
                return@LaunchedEffect
            }
        }
    }
    Popup(
        popupPositionProvider = position, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 3.dp,
        ) {
            Column(
                Modifier.width(IntrinsicSize.Max).widthIn(min = 160.dp, max = 480.dp)
                    .heightIn(max = 480.dp).verticalScroll(rememberScrollState())
                    .onPreviewKeyEvent {
                        if (it.type != KeyEventType.KeyDown || actionKeys.isEmpty()) return@onPreviewKeyEvent false
                        val current = actionKeys.indexOf(focusedKey).coerceAtLeast(0)
                        when (it.key) {
                            Key.DirectionDown -> focusIndex((current + 1) % actionKeys.size)
                            Key.DirectionUp -> focusIndex((current + actionKeys.size - 1) % actionKeys.size)
                            Key.Tab -> focusIndex((current + if (it.isShiftPressed) actionKeys.size - 1 else 1) % actionKeys.size)
                            Key.MoveHome -> focusIndex(0)
                            Key.MoveEnd -> focusIndex(actionKeys.lastIndex)
                            else -> return@onPreviewKeyEvent false
                        }
                        true
                    },
            ) {
                entries.forEach { entry ->
                    key(entry.key) {
                        when (entry) {
                            is ApplicationMenuEntry.Information -> Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                entry.lines.forEach { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                            }
                            is ApplicationMenuEntry.Action -> CompactTextButton(
                                onClick = entry.onClick,
                                modifier = Modifier.fillMaxWidth().focusRequester(requesters.getValue(entry.key))
                                    .onFocusChanged { if (it.isFocused) focusedKey = entry.key }
                                    .semantics { contentDescription = "Application menu action: ${entry.key}" },
                            ) { Text(entry.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
        }
        NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None), onBackCompleted = onDismiss)
    }
}

private fun FocusRequester.requestApplicationMenuFocus(): Boolean =
    try { requestFocus() } catch (_: IllegalStateException) { false }
