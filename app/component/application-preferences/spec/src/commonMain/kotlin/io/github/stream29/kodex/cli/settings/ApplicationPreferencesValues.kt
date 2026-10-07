package io.github.stream29.kodex.cli.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Content and current widths for the independent left and right session sidebars.
 *
 * This is the Application projection, not a persisted width configuration.
 * Application startup selects its transient widths from the application viewport.
 *
 * @throws IllegalArgumentException if either width is below [MinimumSidebarWidthColumns].
 */
public data class SidebarSettings(
    public val left: SidebarContent = SidebarContent.HistoryIndex,
    public val right: SidebarContent = SidebarContent.TerminalSessions,
    public val leftWidth: Int = DefaultSidebarWidthColumns,
    public val rightWidth: Int = DefaultSidebarWidthColumns,
) {
    init {
        require(leftWidth >= MinimumSidebarWidthColumns) {
            "The left sidebar width must be at least $MinimumSidebarWidthColumns columns."
        }
        require(rightWidth >= MinimumSidebarWidthColumns) {
            "The right sidebar width must be at least $MinimumSidebarWidthColumns columns."
        }
    }
}

/** Default width for standalone [SidebarSettings] values, not the startup viewport policy. */
public const val DefaultSidebarWidthColumns: Int = 28

/** Smallest sidebar width that retains the collapse control and one splitter column. */
public const val MinimumSidebarWidthColumns: Int = 4

/** Content that a session sidebar can display. */
@Serializable
public enum class SidebarContent {
    /** Displays an empty sidebar body. */
    @SerialName("none")
    None,

    /** Displays terminal sessions owned by the selected agent. */
    @SerialName("terminal_sessions")
    TerminalSessions,

    /** Displays the selected agent's sparse index timeline. */
    @SerialName("history_index")
    HistoryIndex,
}

/** Key chord for inserting a newline, paired with the only non-conflicting [submitKey]. */
@Serializable
public enum class NewLineKey(
    public val submitKey: SubmitKey,
) {
    @SerialName("shift_enter")
    ShiftEnter(SubmitKey.Enter),

    @SerialName("enter")
    Enter(SubmitKey.CtrlEnter),
}

/** Key chord for submitting input, paired with the corresponding [newLineKey]. */
@Serializable
public enum class SubmitKey {
    Enter,
    CtrlEnter,
    ;

    public val newLineKey: NewLineKey
        get() = when (this) {
            Enter -> NewLineKey.ShiftEnter
            CtrlEnter -> NewLineKey.Enter
        }
}
