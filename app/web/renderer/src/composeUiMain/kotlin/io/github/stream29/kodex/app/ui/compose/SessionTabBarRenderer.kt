@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import io.github.stream29.kodex.app.sessiontabbar.contract.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

@Composable
public actual fun rememberComposeSessionTabBarRenderer(): SessionTabBarRenderer =
    remember { ComposeSessionTabBarRenderer() }

private class ComposeSessionTabBarRenderer : SessionTabBarRenderer {
    @Composable
    override fun Frame(
        state: SessionTabBarState, sessions: @Composable () -> Unit,
        tab: @Composable (SessionTabPresentation, Int) -> Unit, newSession: @Composable () -> Unit,
    ) {
        val scroll = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val identities = state.tabs.map { it.identity }
        val density = LocalDensity.current.density
        val layoutDirection = LocalLayoutDirection.current
        val wheelInput = remember(scroll) {
            Channel<Float>(capacity = 32)
        }
        val wheelPending = remember(wheelInput) { WheelPending() }
        fun clearWheelInput() { wheelPending.clear(wheelInput) }
        DisposableEffect(wheelInput) { onDispose { wheelInput.cancel() } }
        LaunchedEffect(wheelInput, state.selected, identities, density, layoutDirection) {
            clearWheelInput()
            val index = identities.indexOf(state.selected)
            if (index >= 0) scroll.scrollToItem(index)
            for (delta in wheelInput) {
                // A gesture or selection can cancel an individual scroll without killing
                // the receiver; queued input from that interrupted gesture is discarded.
                val operation = launch(start = CoroutineStart.LAZY) {
                    withFrameNanos { }
                    scroll.scroll(MutatePriority.UserInput) { scrollBy(delta) }
                }
                wheelPending.active = operation
                try {
                    operation.start()
                    operation.join()
                } finally {
                    if (wheelPending.active === operation) wheelPending.active = null
                    wheelPending.finish(delta)
                }
                if (operation.isCancelled) {
                    clearWheelInput()
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val sessionsMaximum = (maxWidth * 0.4f).coerceAtMost(96.dp)
            Row(Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.isShiftPressed || event.isCtrlPressed ||
                    event.isAltPressed || event.isMetaPressed) return@onPreviewKeyEvent false
                val direction = when (event.key) {
                    Key.PageUp -> -1
                    Key.PageDown -> 1
                    else -> return@onPreviewKeyEvent false
                }
                if (if (direction > 0) !scroll.canScrollForward else !scroll.canScrollBackward)
                    return@onPreviewKeyEvent false
                wheelPending.interrupt(wheelInput)
                scope.launch {
                    scroll.scrollBy(direction * (scroll.layoutInfo.viewportEndOffset - scroll.layoutInfo.viewportStartOffset).toFloat())
                }
                true
            }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.widthIn(max = sessionsMaximum)) { sessions() }
                LazyRow(
                    Modifier.weight(1f).pointerInput(scroll, wheelInput, density, layoutDirection) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.any { it.isConsumed }) continue
                                if (event.type == PointerEventType.Press) {
                                    wheelPending.interrupt(wheelInput)
                                    continue
                                }
                                if (event.type != PointerEventType.Scroll) continue
                                val x = event.changes.sumOf { it.scrollDelta.x.toDouble() }.toFloat()
                                val y = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
                                if (x != 0f) {
                                    wheelPending.interrupt(wheelInput)
                                    continue
                                }
                                if (y == 0f || !y.isFinite()) continue
                                val direction = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                                val delta = y * density * direction
                                if (!delta.isFinite()) continue
                                val canScroll = if (delta > 0f) {
                                    scroll.canScrollForward || wheelPending.backward > 0
                                } else {
                                    scroll.canScrollBackward || wheelPending.forward > 0
                                }
                                if (canScroll && wheelInput.trySend(delta).isSuccess) {
                                    wheelPending.add(delta)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    }, state = scroll,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(state.tabs, key = { _, presentation -> presentation.identity }) { index, presentation ->
                        tab(presentation, index)
                    }
                }
                newSession()
            }
        }
    }

    @Composable
    override fun Sessions(onClick: () -> Unit) {
        CompactTextButton(onClick, Modifier.semantics { contentDescription = "Sessions" }) {
            Text("Sessions", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    override fun Tab(
        tab: SessionTabPresentation, selected: Boolean, first: Boolean,
        onSelect: () -> Unit, onMenu: (SessionTabMenuPlacement) -> Unit,
    ) {
        val origin = remember(tab.identity) { ComposeMenuOrigin() }
        DisposableEffect(origin) { onDispose { origin.bounds = null } }
        CompactTextButton(
            onClick = onSelect,
            colors = ButtonDefaults.textButtonColors(
                containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = Modifier.widthIn(max = 260.dp).onGloballyPositioned { origin.bounds = it.boundsInWindow() }
                .semantics {
                    this[SemanticsProperties.ContentDescription] = listOf(tab.label, "Session tab: ${tab.identity.value}")
                    role = Role.Tab
                    this.selected = selected
                    stateDescription = if (tab.running) "Running" else "Idle"
                }
                .onPointerEvent(PointerEventType.Press) {
                    if (it.buttons.isSecondaryPressed) origin.bounds?.let { rectangle ->
                        onMenu(ComposeSessionTabPlacement(origin, rectangle, it.changes.firstOrNull()?.position))
                    }
                }
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && (it.key == Key.Menu || it.key == Key.F10 && it.isShiftPressed)) {
                        origin.bounds?.let { rectangle -> onMenu(ComposeSessionTabPlacement(origin, rectangle, null)) }
                        true
                    } else false
                },
        ) {
            if (tab.running) {
                RunningSpinner()
                Spacer(Modifier.width(4.dp))
            }
            Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        }
    }

    @Composable
    override fun NewSession(selected: Boolean, onClick: () -> Unit) {
        CompactTextButton(
            onClick, Modifier.widthIn(min = 32.dp).semantics {
                contentDescription = "New Session"
                role = Role.Tab
                this.selected = selected
            },
            colors = ButtonDefaults.textButtonColors(
                containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            ),
        ) { Text("+") }
    }
}

private class WheelPending {
    var active: Job? = null
    var forward = 0
        private set
    var backward = 0
        private set

    fun add(delta: Float) {
        if (delta > 0f) forward++ else backward++
    }

    fun finish(delta: Float) {
        if (delta > 0f) forward-- else backward--
    }

    fun clear(input: Channel<Float>) {
        while (true) {
            val delta = input.tryReceive().getOrNull() ?: break
            finish(delta)
        }
    }

    fun interrupt(input: Channel<Float>) {
        active?.cancel()
        clear(input)
    }
}
