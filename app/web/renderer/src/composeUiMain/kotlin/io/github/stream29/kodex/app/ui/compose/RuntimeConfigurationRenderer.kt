package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import io.github.stream29.kodex.app.ui.compose.EditorDropdownMenu as DropdownMenu
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationMenu
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationRenderer
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeMenuEntry

@Composable
public actual fun rememberComposeRuntimeConfigurationRenderer(): RuntimeConfigurationRenderer =
    remember { ComposeRuntimeConfigurationRenderer() }

private class ComposeRuntimeConfigurationRenderer : RuntimeConfigurationRenderer {
    @Composable
    override fun Fields(menu: RuntimeConfigurationMenu, enabled: Boolean) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RuntimeMenuButton("Runtime model", menu.modelLabel, menu.models, enabled, Modifier.weight(3f))
            RuntimeMenuButton("Questions", menu.modeLabel, menu.modes, enabled, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RuntimeMenuButton(
    field: String, label: String, roots: List<RuntimeMenuEntry>, enabled: Boolean, modifier: Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var path by remember { mutableStateOf<List<Any>>(emptyList()) }
    var entries = roots
    var initialKey = roots.firstOrNull { it.selected }?.key ?: roots.firstOrNull()?.key
    var depth = 0
    for (key in path) {
        val branch = entries.filterIsInstance<RuntimeMenuEntry.Branch>().firstOrNull { it.key == key } ?: break
        entries = branch.children()
        initialKey = branch.initialChildKey
        depth++
    }
    val renderedPath = path.take(depth)
    LaunchedEffect(enabled, roots.isEmpty(), renderedPath) {
        if (!enabled || roots.isEmpty()) {
            expanded = false
            path = emptyList()
        } else if (path != renderedPath) path = renderedPath
    }
    Box(modifier) {
        OutlinedButton(
            onClick = { path = emptyList(); expanded = true }, enabled = enabled && roots.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$field: $label" },
        ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                if (path.isNotEmpty()) path = path.dropLast(1) else expanded = false
            },
        ) {
            if (renderedPath.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("Back") },
                    enabled = enabled && expanded,
                    onClick = { if (enabled && expanded && path == renderedPath) path = path.dropLast(1) },
                    modifier = Modifier.semantics { contentDescription = "$field: Back" },
                )
            }
            entries.forEach { entry ->
                key(renderedPath, entry.key) {
                    val focus = remember { FocusRequester() }
                    DropdownMenuItem(
                        text = { Text(entry.label) },
                        leadingIcon = if (entry.selected) ({ Text("✓") }) else null,
                        trailingIcon = if (entry is RuntimeMenuEntry.Branch) ({ Text("›") }) else null,
                        enabled = enabled && expanded,
                        onClick = {
                            if (enabled && expanded && path == renderedPath) {
                                when (entry) {
                                    is RuntimeMenuEntry.Branch -> path = renderedPath + entry.key
                                    is RuntimeMenuEntry.Command -> {
                                        expanded = false
                                        path = emptyList()
                                        entry.onSelect()
                                    }
                                }
                            }
                        },
                        modifier = Modifier.focusRequester(focus).semantics {
                            contentDescription = "$field: ${entry.label}"
                            selected = entry.selected
                        },
                    )
                    if (expanded && entry.key == initialKey) {
                        LaunchedEffect(renderedPath, initialKey) {
                            withFrameNanos { }
                            focus.requestFocus()
                        }
                    }
                }
            }
        }
    }
}
