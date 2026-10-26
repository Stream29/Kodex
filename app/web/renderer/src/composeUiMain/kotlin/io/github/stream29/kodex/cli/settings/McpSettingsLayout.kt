package io.github.stream29.kodex.cli.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.app.ui.*

internal object BrowserMcpSettingsLayoutRenderer : McpSettingsLayoutRenderer {
    @Composable override fun McpListFrame(content: @Composable () -> Unit) { UiColumn(content) }
    @Composable override fun McpListHeader(title: String, add: String, importLabel: String, onAdd: () -> Unit, onImport: () -> Unit) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            UiText(title, UiTextRole.Title)
            UiAction(add, onClick = onAdd, role = UiActionRole.Secondary)
            UiAction(importLabel, onClick = onImport)
        }
    }
    @Composable override fun McpListEntry(label: String, onClick: () -> Unit) { UiAction(label, onClick = onClick) }
    @Composable override fun McpInput(label: String, token: Any, value: String, autoFocus: Boolean, onChange: (String) -> Unit) {
        key(token) {
            UiTextInput(value, onChange, label, autoFocus = autoFocus)
        }
    }
    @Composable override fun McpActions(content: @Composable () -> Unit) { UiActions(content) }
    @Composable override fun McpInlineActions(first: @Composable () -> Unit, second: @Composable () -> Unit) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { first(); second() }
    }
    @Composable override fun McpImportEntry(label: String, enabled: Boolean, onClick: () -> Unit) {
        UiAction(label, onClick = onClick, enabled = enabled)
    }
    @Composable override fun McpAction(label: String, role: McpActionRole, enabled: Boolean, autoFocus: Boolean, onClick: () -> Unit) {
        UiAction(
            label, onClick = onClick, enabled = enabled, autoFocus = autoFocus,
            role = when (role) {
                McpActionRole.Primary -> UiActionRole.Primary
                McpActionRole.Danger, McpActionRole.ProminentDanger -> UiActionRole.Destructive
                McpActionRole.Normal -> UiActionRole.Normal
            },
        )
    }
}
