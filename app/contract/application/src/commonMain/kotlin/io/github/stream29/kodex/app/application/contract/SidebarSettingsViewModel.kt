package io.github.stream29.kodex.app.application.contract

import io.github.stream29.kodex.cli.settings.SidebarContent
import io.github.stream29.kodex.cli.settings.SidebarSettings
import kotlinx.coroutines.flow.StateFlow

/** Exposes the persisted content selections for both session sidebars. */
public interface SidebarSettingsViewModel {
    /** Latest application-wide sidebar selections. */
    public val state: StateFlow<SidebarSettings>

    /** Selects the content shown in the left sidebar. */
    public suspend fun selectLeft(content: SidebarContent)

    /** Selects the content shown in the right sidebar. */
    public suspend fun selectRight(content: SidebarContent)
}
