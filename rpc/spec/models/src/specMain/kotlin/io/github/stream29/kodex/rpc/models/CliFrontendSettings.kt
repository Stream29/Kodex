package io.github.stream29.kodex.rpc.models

import io.github.stream29.kodex.cli.settings.NewLineKey
import kotlinx.serialization.Serializable

/**
 * Backend-managed frontend section of settings.yml and its GlobalRpc projection.
 *
 * Sidebar widths are transient. Error Hooks are configured by the backend and executed locally;
 * neither file persistence nor command execution is implemented by this model.
 * TUI clients subscribe and submit CAS edits; they never persist this section themselves.
 *
 * @property newLineKey Local input preference; the matching submit key is derived from it.
 * @property sidebars Local sidebar content preferences, not Session data or viewport state.
 * @property hooks Ordered local commands with distinct names; an empty list disables Hooks.
 */
@Serializable
public data class CliFrontendSettings(
    public val newLineKey: NewLineKey = NewLineKey.ShiftEnter,
    public val sidebars: CliSidebarSettings = CliSidebarSettings(),
    public val hooks: List<NotificationHook> = emptyList(),
) {
    init {
        require(hooks.map { it.name }.toSet().size == hooks.size) {
            "Notification Hook names must be unique."
        }
    }
}
