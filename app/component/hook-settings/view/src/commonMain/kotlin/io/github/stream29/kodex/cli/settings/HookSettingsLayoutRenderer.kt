package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.*

private val LocalHookSettingsLayoutRenderer = staticCompositionLocalOf<HookSettingsLayoutRenderer> { error("Missing host HookSettingsLayoutRenderer") }

@Composable
public actual fun WithHookSettingsLayoutRenderer(renderer: HookSettingsLayoutRenderer, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHookSettingsLayoutRenderer provides renderer, content = content)
}

@Composable
internal actual fun HookListFrame(content: @Composable () -> Unit): Unit =
    LocalHookSettingsLayoutRenderer.current.HookListFrame(content)

@Composable
internal actual fun HookListHeader(title: String, action: String, onAdd: () -> Unit): Unit =
    LocalHookSettingsLayoutRenderer.current.HookListHeader(title, action, onAdd)

@Composable
internal actual fun HookListEntry(label: String, onClick: () -> Unit): Unit =
    LocalHookSettingsLayoutRenderer.current.HookListEntry(label, onClick)

@Composable
internal actual fun HookInput(label: String, value: String, autoFocus: Boolean, onChange: (String) -> Unit): Unit =
    LocalHookSettingsLayoutRenderer.current.HookInput(label, value, autoFocus, onChange)

@Composable
internal actual fun HookTypeChoice(label: String, checked: Boolean, onClick: () -> Unit): Unit =
    LocalHookSettingsLayoutRenderer.current.HookTypeChoice(label, checked, onClick)

@Composable
internal actual fun HookActions(content: @Composable () -> Unit): Unit =
    LocalHookSettingsLayoutRenderer.current.HookActions(content)

@Composable
internal actual fun HookAction(label: String, role: HookActionRole, autoFocus: Boolean, onClick: () -> Unit): Unit =
    LocalHookSettingsLayoutRenderer.current.HookAction(label, role, autoFocus, onClick)
