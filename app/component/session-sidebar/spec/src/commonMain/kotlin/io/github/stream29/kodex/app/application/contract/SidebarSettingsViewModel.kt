package io.github.stream29.kodex.app.application.contract

import io.github.stream29.kodex.cli.settings.SidebarContent
import io.github.stream29.kodex.cli.settings.SidebarSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * Exposes local content preferences and transient widths for both session sidebars.
 *
 * One application-scoped owner supplies both renderers. Its existing implementation
 * receives the real preferences/content source and application-width input directly;
 * this contract adds no factory, second state authority or duplicated preferences values.
 * Content persists in frontend settings; widths are invocation-local. Render [state]
 * directly, with local focus/scroll/hover/drag and popup geometry in the renderer.
 * A collapsed/unmounted sidebar does not close its borrowed Agent or process children.
 * Content writes use the existing preferences owner; ordinary persistence failures propagate
 * to the caller, not an empty/None content fallback.
 *
 * None renders an empty body; TerminalSessions borrows the Agent's raw registry and
 * displays only ongoing processes sorted by session id, without deleting retained
 * completed entries. HistoryIndex borrows that Agent's existing child and acquires
 * independent read handles for each side. No selected Agent yields an empty body,
 * not another registry/loader. Arrows, splitters, popup anchors and terminal-width
 * clamping are view-only concerns; they do not become persistent/spec geometry.
 */
public interface SidebarSettingsViewModel {
    /** Latest application-wide sidebar configuration; both sides borrow the same observation. */
    public val state: StateFlow<SidebarSettings>

    /**
     * Captures the initial viewport width once; later resizes do not reset user sizing.
     * An explicit application-width input takes precedence over viewport initialization.
     * @throws IllegalArgumentException when the first uncaptured viewport has negative columns.
     */
    public fun initializeViewport(columns: Int): Unit

    /**
     * Selects the content shown in the left sidebar.
     * @throws Exception when persistence fails; caller cancellation stops its wait.
     */
    public suspend fun selectLeft(content: SidebarContent)

    /**
     * Selects the content shown in the right sidebar.
     * @throws Exception when persistence fails; caller cancellation stops its wait.
     */
    public suspend fun selectRight(content: SidebarContent)

    /** Changes the left width for this frontend invocation only, clamped to the preferences minimum. */
    public suspend fun resizeLeft(columns: Int)

    /** Changes the right width for this frontend invocation only, clamped to the preferences minimum. */
    public suspend fun resizeRight(columns: Int)
}
