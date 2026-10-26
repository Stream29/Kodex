package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.*

private val LocalContextSourceSettingsLayoutRenderer = staticCompositionLocalOf<ContextSourceSettingsLayoutRenderer> { error("Missing host ContextSourceSettingsLayoutRenderer") }

@Composable
public actual fun WithContextSourceSettingsLayoutRenderer(renderer: ContextSourceSettingsLayoutRenderer, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContextSourceSettingsLayoutRenderer provides renderer, content = content)
}

@Composable
internal actual fun ContextSourceCustomItem(path: String, checked: Boolean, onChange: (Boolean) -> Unit, onRemove: () -> Unit): Unit =
    LocalContextSourceSettingsLayoutRenderer.current.ContextSourceCustomItem(path, checked, onChange, onRemove)

@Composable
internal actual fun ContextSourcePathInput(value: String, onChange: (String) -> Unit): Unit =
    LocalContextSourceSettingsLayoutRenderer.current.ContextSourcePathInput(value, onChange)

@Composable
internal actual fun ContextSourceDialogActions(content: @Composable () -> Unit): Unit =
    LocalContextSourceSettingsLayoutRenderer.current.ContextSourceDialogActions(content)

@Composable
internal actual fun ContextSourceDialogAction(label: String, primary: Boolean, onClick: () -> Unit): Unit =
    LocalContextSourceSettingsLayoutRenderer.current.ContextSourceDialogAction(label, primary, onClick)
