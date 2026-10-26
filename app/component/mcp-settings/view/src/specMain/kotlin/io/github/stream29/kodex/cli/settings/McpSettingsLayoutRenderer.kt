package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.*

/** Renderer leaves only; shared content retains all child ownership and command/revision wiring. */
public interface McpSettingsLayoutRenderer {
    @Composable
    public fun McpListFrame(content: @Composable () -> Unit): Unit

    @Composable
    public fun McpListHeader(title: String, add: String, importLabel: String, onAdd: () -> Unit, onImport: () -> Unit): Unit

    @Composable
    public fun McpListEntry(label: String, onClick: () -> Unit): Unit

    @Composable
    public fun McpInput(label: String, token: Any, value: String, autoFocus: Boolean = false, onChange: (String) -> Unit): Unit

    @Composable
    public fun McpActions(content: @Composable () -> Unit): Unit

    @Composable
    public fun McpInlineActions(first: @Composable () -> Unit, second: @Composable () -> Unit): Unit

    @Composable
    public fun McpImportEntry(label: String, enabled: Boolean, onClick: () -> Unit): Unit

    @Composable
    public fun McpAction(
    label: String, role: McpActionRole = McpActionRole.Normal, enabled: Boolean = true,
    autoFocus: Boolean = false, onClick: () -> Unit,
): Unit
}

@Composable
public expect fun WithMcpSettingsLayoutRenderer(renderer: McpSettingsLayoutRenderer, content: @Composable () -> Unit): Unit
