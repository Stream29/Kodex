package io.github.stream29.kodex.cli.settings

import androidx.compose.runtime.*

/** Renderer leaves only; shared content retains all child ownership and command/revision wiring. */
public interface HookSettingsLayoutRenderer {
    @Composable
    public fun HookListFrame(content: @Composable () -> Unit): Unit

    @Composable
    public fun HookListHeader(title: String, action: String, onAdd: () -> Unit): Unit

    @Composable
    public fun HookListEntry(label: String, onClick: () -> Unit): Unit

    @Composable
    public fun HookInput(label: String, value: String, autoFocus: Boolean = false, onChange: (String) -> Unit): Unit

    @Composable
    public fun HookTypeChoice(label: String, checked: Boolean, onClick: () -> Unit): Unit

    @Composable
    public fun HookActions(content: @Composable () -> Unit): Unit

    @Composable
    public fun HookAction(
    label: String, role: HookActionRole = HookActionRole.Normal, autoFocus: Boolean = false, onClick: () -> Unit,
): Unit
}

@Composable
public expect fun WithHookSettingsLayoutRenderer(renderer: HookSettingsLayoutRenderer, content: @Composable () -> Unit): Unit
