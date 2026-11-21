package io.github.stream29.kodex.rpc.models

import io.github.stream29.kodex.cli.settings.ToolWindowId
import io.github.stream29.kodex.cli.settings.ToolWindowSide
import kotlinx.serialization.Serializable

/**
 * Persisted CLI sidebar content choices, without transient widths.
 *
 * The CLI initializes each sidebar width to one quarter of the application width at startup.
 * Resizing only changes frontend runtime state; neither widths nor scroll positions belong here.
 */
@Serializable
public data class CliSidebarSettings(
    public val activeLeft: ToolWindowId? = null,
    public val activeRight: ToolWindowId? = null,
) {
    init {
        require(activeLeft == null || activeLeft.side == ToolWindowSide.Left)
        require(activeRight == null || activeRight.side == ToolWindowSide.Right)
    }
}
