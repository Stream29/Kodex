package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import io.github.stream29.kodex.app.ui.compose.EditorDropdownMenu as DropdownMenu
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.ui.SettingsRenderer
import io.github.stream29.kodex.app.ui.UiChoiceOption
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.app.ui.UiTextRole

@Composable
public actual fun rememberComposeSettingsRenderer(): SettingsRenderer = remember { ComposeSettingsRenderer() }

private class ComposeSettingsRenderer : SettingsRenderer {
    @Composable
    override fun Section(title: String, content: @Composable () -> Unit) {
        Surface(
            Modifier.fillMaxWidth(), shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                UiText(title, UiTextRole.Title)
                content()
            }
        }
    }

    @Composable
    override fun Item(label: String, supportingText: String?, enabled: Boolean, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth < 480.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label, style = MaterialTheme.typography.bodyMedium,
                            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        content()
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        content()
                    }
                }
            }
            supportingText?.let { UiText(it, UiTextRole.Supporting) }
        }
    }

    @Composable
    override fun Action(label: String, onClick: () -> Unit, enabled: Boolean, autoFocus: Boolean) {
        io.github.stream29.kodex.app.ui.UiAction(label, onClick, enabled, autoFocus)
    }

    @Composable
    override fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean, supportingText: String?) {
        val admitted by rememberUpdatedState(enabled)
        val invoke by rememberUpdatedState(onChange)
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 32.dp)
                    .toggleable(checked, enabled = enabled, role = Role.Checkbox,
                        onValueChange = { if (admitted) invoke(it) }),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
                    Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
                }
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            supportingText?.let { UiText(it, UiTextRole.Supporting) }
        }
    }

    @Composable
    override fun Choice(
        fieldKey: String,
        label: String,
        options: List<UiChoiceOption>,
        selectedKey: String,
        onSelect: (String) -> Unit,
        enabled: Boolean,
        supportingText: String?,
    ) {
        key(fieldKey) {
            var expanded by remember { mutableStateOf(false) }
            var mounted by remember { mutableStateOf(true) }
            val currentOptions by rememberUpdatedState(options)
            DisposableEffect(Unit) { onDispose { mounted = false } }
            val selected = options.firstOrNull { it.key == selectedKey }?.label ?: selectedKey
            LaunchedEffect(enabled, options.isEmpty()) { if (!enabled || options.isEmpty()) expanded = false }
            Item(label, supportingText, enabled) {
                Box {
                    CompactTextButton(
                        onClick = { expanded = true }, enabled = enabled && options.isNotEmpty(),
                        modifier = Modifier.semantics { contentDescription = "$label: $selected" },
                    ) { Text(selected, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        options.forEach { option ->
                            key(option.key) {
                                CompactChoiceMenuItem(
                                    label = option.label,
                                    selected = option.key == selectedKey,
                                    autoFocus = option.key == (options.firstOrNull { it.key == selectedKey } ?: options.firstOrNull())?.key,
                                    enabled = enabled && expanded,
                                    onClick = {
                                        if (mounted && expanded && enabled && currentOptions.any { it.key == option.key }) {
                                            expanded = false
                                            onSelect(option.key)
                                        }
                                    },
                                    modifier = Modifier.semantics {
                                        contentDescription = "$label: ${option.label}"
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
