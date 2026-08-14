package io.github.stream29.kodex.desktop.patch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingPatchToolEvent
import io.github.stream29.kodex.cli.patch.PatchPresentation
import io.github.stream29.kodex.cli.patch.PatchPresentationLine
import io.github.stream29.kodex.cli.patch.PatchPresentationLineKind
import io.github.stream29.kodex.cli.patch.PatchPresentationStatus
import io.github.stream29.kodex.cli.patch.toPendingPatchPresentation
import io.github.stream29.kodex.cli.patch.toStablePatchPresentation

/** Material Desktop renderer of the patch presentation shared with the TUI. */
@Composable
public fun StablePatchDesktopView(
    event: StablePatchToolEvent,
    modifier: Modifier = Modifier,
): Unit {
    PatchDesktopView(
        presentation = remember(event) { event.toStablePatchPresentation() },
        modifier = modifier,
    )
}

/** Material Desktop renderer of one in-progress patch presentation. */
@Composable
public fun PendingPatchDesktopView(
    event: PendingPatchToolEvent,
    modifier: Modifier = Modifier,
): Unit {
    PatchDesktopView(
        presentation = remember(event.diff) { event.diff.toPendingPatchPresentation() },
        modifier = modifier,
    )
}

@Composable
private fun PatchDesktopView(
    presentation: PatchPresentation,
    modifier: Modifier,
): Unit {
    var expanded by remember(presentation) { mutableStateOf(false) }
    var changesExpanded by remember(presentation) { mutableStateOf(false) }
    var visibleLineCount by remember(presentation) { mutableStateOf(DefaultPatchLinePageSize) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = "${if (expanded) "▾" else "▸"} ${presentation.header}",
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            color = presentation.headerColor(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        if (expanded) {
            Text(
                text = "Tool: ${presentation.rawToolName}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "${if (changesExpanded) "▾" else "▸"} Changes",
                modifier = Modifier.fillMaxWidth().clickable {
                    changesExpanded = !changesExpanded
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (changesExpanded) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
                ) {
                    presentation.lines.take(visibleLineCount).forEach { line ->
                        PatchLine(line)
                    }
                    val remaining = presentation.lines.size - visibleLineCount
                    if (remaining > 0) {
                        Text(
                            text = "▸ Show next " +
                                "${minOf(DefaultPatchLinePageSize, remaining)} lines" +
                                " · $remaining remaining",
                            modifier = Modifier.fillMaxWidth().clickable {
                                visibleLineCount = minOf(
                                    visibleLineCount + DefaultPatchLinePageSize,
                                    presentation.lines.size,
                                )
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PatchLine(line: PatchPresentationLine): Unit {
    Text(
        text = line.text,
        modifier = Modifier.fillMaxWidth(),
        color = line.kind.contentColor(),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (line.kind == PatchPresentationLineKind.File) {
            FontWeight.SemiBold
        } else {
            FontWeight.Normal
        },
    )
}

@Composable
private fun PatchPresentation.headerColor(): Color = when (status) {
    PatchPresentationStatus.Running -> MaterialTheme.colorScheme.primary
    PatchPresentationStatus.Completed -> MaterialTheme.colorScheme.onSurface
    PatchPresentationStatus.Failed -> MaterialTheme.colorScheme.error
}

@Composable
private fun PatchPresentationLineKind.contentColor(): Color = when (this) {
    PatchPresentationLineKind.Addition -> MaterialTheme.colorScheme.tertiary
    PatchPresentationLineKind.Removal,
    PatchPresentationLineKind.Failure,
        -> MaterialTheme.colorScheme.error

    PatchPresentationLineKind.Metadata,
    PatchPresentationLineKind.Context,
        -> MaterialTheme.colorScheme.onSurfaceVariant

    PatchPresentationLineKind.File -> MaterialTheme.colorScheme.onSurface
}

private const val DefaultPatchLinePageSize: Int = 200
