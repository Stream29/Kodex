package io.github.stream29.kodex.app.ui.compose

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.cli.settings.ToolWindowId
import io.github.stream29.kodex.cli.settings.ToolWindowSide

@Composable
internal fun ComposeToolWindowRail(
    side: ToolWindowSide,
    active: ToolWindowId?,
    onToggle: (ToolWindowId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val indicatorAlignment = if ((side == ToolWindowSide.Left) == isLtr) Alignment.CenterStart else Alignment.CenterEnd
    Column(
        modifier.width(40.dp).fillMaxHeight().background(colors.surfaceContainer).padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (id in ToolWindowId.entries) {
            if (id.side != side) continue
            val label = when (id) {
                ToolWindowId.Sessions -> "Sessions"
                ToolWindowId.HistoryIndex -> "History Index"
                ToolWindowId.TerminalSessions -> "Terminal"
            }
            val icon = when (id) {
                ToolWindowId.Sessions -> ToolWindowIcon.Sessions
                ToolWindowId.HistoryIndex -> ToolWindowIcon.History
                ToolWindowId.TerminalSessions -> ToolWindowIcon.Terminal
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ToolWindowIconButton(label, icon, { onToggle(id) }, size = 36.dp, selected = id == active)
                if (id == active) {
                    Box(Modifier.align(indicatorAlignment).width(2.dp).height(18.dp)
                        .background(colors.primary, RoundedCornerShape(1.dp)))
                }
            }
        }
    }
}

@Composable
internal fun ToolWindowIconButton(
    label: String,
    icon: ToolWindowIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    selected: Boolean? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)
    val indication = LocalIndication.current
    val action = if (selected == null) {
        Modifier.clickable(interactionSource = interaction, indication = indication, role = Role.Button, onClick = onClick)
    } else {
        Modifier.selectable(
            selected, interactionSource = interaction, indication = indication,
            role = Role.Button, onClick = onClick,
        )
    }
    ToolWindowTooltip(label) {
        Box(
            modifier.size(size).clip(shape)
                .background(when {
                    selected == true -> colors.secondaryContainer
                    hovered -> colors.onSurface.copy(alpha = 0.08f)
                    else -> Color.Transparent
                })
                .onFocusChanged { focused = it.isFocused }
                .border(1.dp, if (focused) colors.primary else Color.Transparent, shape)
                .semantics { contentDescription = label }
                .then(action),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon.vector, contentDescription = null,
                tint = if (selected == true) colors.onSecondaryContainer else colors.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolWindowTooltip(label: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        focusable = false,
        content = content,
    )
}

internal enum class ToolWindowIcon(val vector: ImageVector) {
    Sessions(toolWindowVector("Sessions") {
        moveTo(2f, 5f); lineTo(8f, 5f); lineTo(10f, 7f); lineTo(18f, 7f)
        lineTo(18f, 16f); lineTo(2f, 16f); close()
    }),
    History(toolWindowVector("History Index") {
        moveTo(6f, 5f); lineTo(17f, 5f)
        moveTo(6f, 10f); lineTo(17f, 10f)
        moveTo(6f, 15f); lineTo(17f, 15f)
        moveTo(3f, 5f); lineTo(3.1f, 5f)
        moveTo(3f, 10f); lineTo(3.1f, 10f)
        moveTo(3f, 15f); lineTo(3.1f, 15f)
    }),
    Terminal(toolWindowVector("Terminal") {
        moveTo(2f, 4f); lineTo(18f, 4f); lineTo(18f, 16f); lineTo(2f, 16f); close()
        moveTo(5f, 7.5f); lineTo(8f, 10f); lineTo(5f, 12.5f)
        moveTo(10f, 12.5f); lineTo(14.5f, 12.5f)
    }),
    Refresh(toolWindowVector("Refresh") {
        moveTo(16f, 4f); lineTo(16f, 8f); lineTo(12f, 8f)
        moveTo(16f, 8f)
        curveTo(14.9f, 5.4f, 12.9f, 4f, 10f, 4f)
        curveTo(6.7f, 4f, 4f, 6.7f, 4f, 10f)
        curveTo(4f, 13.3f, 6.7f, 16f, 10f, 16f)
        curveTo(12.9f, 16f, 15.2f, 14f, 15.8f, 11.6f)
    }),
    Collapse(toolWindowVector("Collapse") { moveTo(5f, 10f); lineTo(15f, 10f) }),
    New(toolWindowVector("New") {
        moveTo(5f, 10f); lineTo(15f, 10f)
        moveTo(10f, 5f); lineTo(10f, 15f)
    }),
    Options(toolWindowVector("Options", strokeWidth = 2f) {
        moveTo(5f, 10f); lineTo(5.1f, 10f)
        moveTo(10f, 10f); lineTo(10.1f, 10f)
        moveTo(15f, 10f); lineTo(15.1f, 10f)
    }),
    ChevronRight(toolWindowVector("Expand") {
        moveTo(8f, 5f); lineTo(13f, 10f); lineTo(8f, 15f)
    }),
    ChevronDown(toolWindowVector("Fold") {
        moveTo(5f, 8f); lineTo(10f, 13f); lineTo(15f, 8f)
    }),
    Check(toolWindowVector("Checked") {
        moveTo(4.5f, 10f); lineTo(8.5f, 14f); lineTo(15.5f, 6f)
    }),
}

private fun toolWindowVector(
    name: String,
    strokeWidth: Float = 1.5f,
    draw: PathBuilder.() -> Unit,
): ImageVector =
    ImageVector.Builder(name, 20.dp, 20.dp, 20f, 20f).apply {
        path(
            stroke = SolidColor(Color.Black), strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            pathBuilder = draw,
        )
    }.build()
