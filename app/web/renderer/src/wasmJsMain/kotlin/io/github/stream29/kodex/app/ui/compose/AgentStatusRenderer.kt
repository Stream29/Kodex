package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel
import io.github.stream29.kodex.cli.app.AgentHistorySeparatorRenderer
import io.github.stream29.kodex.cli.app.AgentStatusRenderer
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationMenu
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationMenuContent
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeMenuEntry
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import androidx.compose.material3.Text as MaterialText

@Composable
internal fun rememberComposeAgentStatusRenderer(): AgentStatusRenderer =
    remember { ComposeAgentStatusRenderer() }

@Composable
internal fun rememberComposeAgentHistorySeparatorRenderer(): AgentHistorySeparatorRenderer =
    remember { ComposeAgentHistorySeparatorRenderer() }

@Composable
internal fun RuntimeConfigurationFields(menu: RuntimeConfigurationMenu, enabled: Boolean = true) {
    RuntimeMenuButton("Runtime model", menu.modelLabel, menu.models, enabled, Modifier)
    RuntimeMenuButton("Questions", menu.modeLabel, menu.modes, enabled, Modifier)
}

@Composable
private fun RuntimeMenuButton(
    field: String, label: String, roots: List<RuntimeMenuEntry>, enabled: Boolean, modifier: Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    // RuntimeConfigurationMenuContent keys this renderer invocation by its borrowed VM.
    val owner = remember { Any() }
    val triggerFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val available by rememberUpdatedState(enabled)
    Box(modifier) {
        CompactTextButton(
            onClick = { expanded = true }, enabled = enabled && roots.isNotEmpty(),
            modifier = Modifier.focusRequester(triggerFocus).semantics { contentDescription = "$field: $label" },
        ) { MaterialText(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        RuntimeCascadeMenu(
            owner = owner,
            field = field,
            roots = roots,
            enabled = enabled,
            expanded = expanded,
            onDismiss = { expanded = false },
            restoreTriggerFocus = {
                scope.launch {
                    // Let the underlying DOM editor finish its ordinary overlay restoration
                    // before taking focus for the menu's explicit keyboard return target.
                    withFrameNanos { }
                    withFrameNanos { }
                    if (available && !expanded) triggerFocus.requestFocus()
                }
            },
        )
    }
}

private class ComposeAgentStatusRenderer : AgentStatusRenderer {
    @Composable
    override fun Frame(regular: @Composable () -> Unit, settings: @Composable () -> Unit) {
        StatusBarLayout(regular, settings)
    }

    @Composable
    override fun Text(value: String) { MaterialText(value, style = MaterialTheme.typography.labelLarge) }

    @Composable
    override fun Action(label: String, enabled: Boolean, onClick: () -> Unit) {
        CompactTextButton(
            onClick = onClick, enabled = enabled,
        ) { MaterialText(label) }
    }

    @Composable
    override fun Configuration(viewModel: RuntimeConfigurationViewModel) {
        RuntimeConfigurationMenuContent(viewModel) { menu ->
            RuntimeConfigurationFields(menu)
        }
    }

    @Composable
    override fun Directory(directory: Path, onBrowse: () -> Unit) {
        CompactTextButton(
            onClick = onBrowse, enabled = LocalGraphicalHostCapabilities.current.directoryBrowsing,
            modifier = Modifier.semantics { contentDescription = "Working directory: $directory" }) {
            MaterialText(directory.toString(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    override fun Settings(onOpen: () -> Unit) {
        CompactTextButton(
            onClick = onOpen,
        ) { MaterialText("Settings") }
    }
}

private class ComposeAgentHistorySeparatorRenderer : AgentHistorySeparatorRenderer {
    @Composable
    override fun Separator(label: String?, showScrollToLatest: Boolean, onScrollToLatest: () -> Unit) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = if (showScrollToLatest) minOf(maxWidth, 40.dp) else 0.dp
            val labelWidth = if (showScrollToLatest) ((maxWidth - gap) / 2).coerceAtLeast(0.dp) else maxWidth
            Row(Modifier.fillMaxWidth().align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(Modifier.weight(1f).testTag("history-separator-left"))
                if (showScrollToLatest) {
                    Spacer(Modifier.width(gap))
                    HorizontalDivider(Modifier.weight(1f).testTag("history-separator-right"))
                }
            }
            if (label != null) {
                MaterialText(
                    label,
                    modifier = Modifier.align(Alignment.CenterStart).widthIn(max = labelWidth)
                        .background(MaterialTheme.colorScheme.surface),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (showScrollToLatest) {
                var focused by remember { mutableStateOf(false) }
                CompactTextButton(
                    onClick = onScrollToLatest,
                    modifier = Modifier.align(Alignment.Center).width(gap)
                        .onFocusChanged { focused = it.isFocused }
                        .border(1.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .semantics { contentDescription = "Scroll to latest" },
                ) { MaterialText("↓") }
            }
        }
    }
}
