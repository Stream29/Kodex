package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.*

private val LocalMcpSettingsLayoutRenderer = staticCompositionLocalOf<McpSettingsLayoutRenderer> { error("Missing host McpSettingsLayoutRenderer") }

@Composable
public actual fun WithMcpSettingsLayoutRenderer(renderer: McpSettingsLayoutRenderer, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMcpSettingsLayoutRenderer provides renderer, content = content)
}

@Composable
internal actual fun McpListFrame(content: @Composable () -> Unit): Unit =
    LocalMcpSettingsLayoutRenderer.current.McpListFrame(content)

@Composable
internal actual fun McpListHeader(title: String, add: String, importLabel: String, onAdd: () -> Unit, onImport: () -> Unit): Unit =
    LocalMcpSettingsLayoutRenderer.current.McpListHeader(title, add, importLabel, onAdd, onImport)

@Composable
internal actual fun McpListEntry(label: String, onClick: () -> Unit): Unit =
    LocalMcpSettingsLayoutRenderer.current.McpListEntry(label, onClick)

@Composable
internal actual fun McpInput(label: String, token: Any, value: String, autoFocus: Boolean, onChange: (String) -> Unit): Unit =
    LocalMcpSettingsLayoutRenderer.current.McpInput(label, token, value, autoFocus, onChange)

@Composable
internal actual fun McpActions(content: @Composable () -> Unit): Unit =
    LocalMcpSettingsLayoutRenderer.current.McpActions(content)

@Composable
internal actual fun McpInlineActions(first: @Composable () -> Unit, second: @Composable () -> Unit): Unit =
    LocalMcpSettingsLayoutRenderer.current.McpInlineActions(first, second)

@Composable
internal actual fun McpImportEntry(label: String, enabled: Boolean, onClick: () -> Unit): Unit =
    LocalMcpSettingsLayoutRenderer.current.McpImportEntry(label, enabled, onClick)

@Composable
internal actual fun McpAction(label: String, role: McpActionRole, enabled: Boolean, autoFocus: Boolean, onClick: () -> Unit): Unit =
    LocalMcpSettingsLayoutRenderer.current.McpAction(label, role, enabled, autoFocus, onClick)
