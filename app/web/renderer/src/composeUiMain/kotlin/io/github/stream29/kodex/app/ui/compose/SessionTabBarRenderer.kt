@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.sessiontabbar.contract.*

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
        val identities = state.tabs.map { it.identity }
        LaunchedEffect(state.selected, identities) {
            val index = identities.indexOf(state.selected)
            if (index >= 0) scroll.scrollToItem(index)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            sessions()
            LazyRow(
                Modifier.weight(1f), state = scroll,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(state.tabs, key = { _, presentation -> presentation.identity }) { index, presentation ->
                    tab(presentation, index)
                }
            }
            newSession()
        }
    }

    @Composable
    override fun Sessions(onClick: () -> Unit) { OutlinedButton(onClick) { Text("Sessions") } }

    @Composable
    override fun Tab(
        tab: SessionTabPresentation, selected: Boolean, first: Boolean,
        onSelect: () -> Unit, onMenu: (SessionTabMenuPlacement) -> Unit,
    ) {
        val origin = remember(tab.identity) { ComposeMenuOrigin() }
        DisposableEffect(origin) { onDispose { origin.bounds = null } }
        TextButton(
            onClick = onSelect,
            colors = ButtonDefaults.textButtonColors(
                containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = Modifier.widthIn(max = 260.dp).onGloballyPositioned { origin.bounds = it.boundsInWindow() }
                .semantics { contentDescription = "Session tab: ${tab.identity.value}"; this.selected = selected }
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
                Text("●", color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { contentDescription = "Running" })
                Spacer(Modifier.width(4.dp))
            }
            Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    override fun NewSession(selected: Boolean, onClick: () -> Unit) {
        FilledTonalButton(onClick, Modifier.semantics { contentDescription = "New Session"; this.selected = selected }) { Text("+") }
    }
}
