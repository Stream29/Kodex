package io.github.stream29.kodex.app.application.contract

import io.github.stream29.kodex.cli.settings.SidebarContent
import io.github.stream29.kodex.cli.settings.SidebarSettings
import kotlinx.coroutines.flow.StateFlow

/** Exposes local content preferences and transient widths for both session sidebars. */
public interface SidebarSettingsViewModel {
    /** Latest application-wide sidebar configuration. */
    public val state: StateFlow<SidebarSettings>

    /** Captures the initial viewport width once; later resizes do not reset user sizing. */
    public fun initializeViewport(columns: Int): Unit

    /** Selects the content shown in the left sidebar. */
    public suspend fun selectLeft(content: SidebarContent)

    /** Selects the content shown in the right sidebar. */
    public suspend fun selectRight(content: SidebarContent)

    /** Changes the left width for this frontend invocation only. */
    public suspend fun resizeLeft(columns: Int)

    /** Changes the right width for this frontend invocation only. */
    public suspend fun resizeRight(columns: Int)
}
