package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.*

/** Renderer leaves only; shared content retains all child ownership and command/revision wiring. */
public interface ContextSourceSettingsLayoutRenderer {
    @Composable
    public fun ContextSourceCustomItem(
    path: String, checked: Boolean, onChange: (Boolean) -> Unit, onRemove: () -> Unit,
): Unit

    @Composable
    public fun ContextSourcePathInput(value: String, onChange: (String) -> Unit): Unit

    @Composable
    public fun ContextSourceDialogActions(content: @Composable () -> Unit): Unit

    @Composable
    public fun ContextSourceDialogAction(label: String, primary: Boolean, onClick: () -> Unit): Unit
}

@Composable
public expect fun WithContextSourceSettingsLayoutRenderer(renderer: ContextSourceSettingsLayoutRenderer, content: @Composable () -> Unit): Unit
