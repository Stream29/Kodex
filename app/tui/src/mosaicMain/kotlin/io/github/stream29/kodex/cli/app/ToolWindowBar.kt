package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Spacer
import com.jakewharton.mosaic.ui.Text
import io.github.stream29.kodex.cli.components.TuiInteractionStyle
import io.github.stream29.kodex.cli.components.TuiPressable
import io.github.stream29.kodex.cli.components.TuiTheme
import io.github.stream29.kodex.cli.components.tuiInteractionTextStyle
import io.github.stream29.kodex.cli.settings.ToolWindowId
import io.github.stream29.kodex.cli.settings.ToolWindowSide

/** One-row presentation only. The caller owns toggle policy and passes the actual row width. */
@Composable
internal fun ToolWindowBar(
    columns: Int,
    activeLeft: ToolWindowId?,
    activeRight: ToolWindowId?,
    onToggle: (ToolWindowId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TuiTheme.colorScheme
    val buttons = toolWindowBarButtons(columns, activeLeft, activeRight)
    Row(modifier.width(columns.coerceAtLeast(0)).height(1).background(colors.surfaceContainer)) {
        val left = buttons.firstOrNull { it.id.side == ToolWindowSide.Left }
        val right = buttons.filter { it.id.side == ToolWindowSide.Right }
        if (left != null) ToolWindowBarButton(left, activeLeft, onToggle)
        Spacer(Modifier.weight(1f))
        val separateRightButtons = right.size == 2 && buttons.sumOf { it.label.length } < columns
        right.forEachIndexed { index, button ->
            if (index != 0 && separateRightButtons) Spacer(Modifier.width(1))
            ToolWindowBarButton(button, activeRight, onToggle)
        }
    }
}

@Composable
private fun ToolWindowBarButton(
    button: ToolWindowBarItem,
    active: ToolWindowId?,
    onToggle: (ToolWindowId) -> Unit,
) {
    val colors = TuiTheme.colorScheme
    val selected = button.id == active
    TuiPressable(
        modifier = Modifier.background(if (selected) colors.secondaryContainer else colors.surfaceContainer),
        onClick = { onToggle(button.id) },
    ) { _, hovered, pressed ->
        Text(
            value = button.label,
            color = if (selected) colors.onSecondaryContainer else colors.onSurface,
            textStyle = tuiInteractionTextStyle(
                hovered = hovered,
                pressed = pressed,
                selected = selected,
                idleTextStyle = TuiTheme.typography.label,
                interactionStyle = TuiInteractionStyle.PreserveColors,
            ),
        )
    }
}

internal data class ToolWindowBarItem(val id: ToolWindowId, val label: String)

/** Labels are ASCII: length equals terminal columns, including the brackets. */
internal fun toolWindowBarButtons(
    columns: Int,
    activeLeft: ToolWindowId?,
    activeRight: ToolWindowId?,
): List<ToolWindowBarItem> {
    val labels = when {
        columns >= 40 -> listOf("[Sessions]", "[History]", "[Terminal]")
        columns >= 17 -> listOf("[Sess]", "[Idx]", "[Term]")
        columns >= 9 -> listOf("[S]", "[H]", "[T]")
        else -> listOf("S", "H", "T")
    }
    val sessions = ToolWindowBarItem(ToolWindowId.Sessions, labels[0])
    val history = ToolWindowBarItem(ToolWindowId.HistoryIndex, labels[1])
    val terminal = ToolWindowBarItem(ToolWindowId.TerminalSessions, labels[2])
    val activeRightButton = if (activeRight == terminal.id) terminal else history
    return when {
        columns <= 0 -> emptyList()
        columns == 1 && activeLeft == null && activeRight != null -> listOf(activeRightButton)
        columns == 1 -> listOf(sessions)
        columns == 2 -> listOf(sessions, activeRightButton)
        else -> listOf(sessions, history, terminal)
    }
}
