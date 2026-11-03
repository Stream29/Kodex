package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationMenu
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationRenderer
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeMenuEntry
import kotlinx.coroutines.launch

@Composable
public actual fun rememberComposeRuntimeConfigurationRenderer(): RuntimeConfigurationRenderer =
    remember { ComposeRuntimeConfigurationRenderer() }

private class ComposeRuntimeConfigurationRenderer : RuntimeConfigurationRenderer {
    @Composable
    override fun Fields(menu: RuntimeConfigurationMenu, enabled: Boolean) {
        RuntimeMenuButton("Runtime model", menu.modelLabel, menu.models, enabled, Modifier)
        RuntimeMenuButton("Questions", menu.modeLabel, menu.modes, enabled, Modifier)
    }
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
        ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
