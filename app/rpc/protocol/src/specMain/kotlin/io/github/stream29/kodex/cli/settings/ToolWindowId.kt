package io.github.stream29.kodex.cli.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

public enum class ToolWindowSide { Left, Right }

/** The side also determines scope: application on the left, focused Agent on the right. */
@Serializable
public enum class ToolWindowId(public val side: ToolWindowSide) {
    @SerialName("sessions")
    Sessions(ToolWindowSide.Left),
    @SerialName("history_index")
    HistoryIndex(ToolWindowSide.Right),
    @SerialName("terminal_sessions")
    TerminalSessions(ToolWindowSide.Right);

    /** Clicking the active tool closes its own side; selecting another leaves that side open. */
    public fun toggle(current: ToolWindowId?): ToolWindowId? {
        require(current == null || current.side == side)
        return if (current == this) null else this
    }
}
