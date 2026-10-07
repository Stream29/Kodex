package io.github.stream29.kodex.cli.components

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.modifier.Modifier
import io.github.stream29.kodex.utils.terminaltext.takeLastFittingTerminalWidth
import io.github.stream29.kodex.utils.terminaltext.terminalCellWidth
import kotlinx.io.files.Path

@Composable
public fun SettingsStatusButton(onOpenSettings: () -> Unit) {
    TuiButton(
        label = SettingsLabel,
        modifier = Modifier.background(SessionButtonBackground),
        color = SessionButtonForeground,
        onClick = onOpenSettings,
    )
}

@Composable
public fun WorkingDirectoryStatusButton(
    columns: Int,
    workingDirectory: Path,
    enabled: Boolean,
    onBrowse: () -> Unit,
) {
    TuiButton(
        label = workingDirectoryStatusLabel(workingDirectory, columns),
        modifier = Modifier.background(SessionButtonBackground),
        color = SessionButtonForeground,
        enabled = enabled,
        onClick = onBrowse,
    )
}

public fun workingDirectoryStatusLabel(
    workingDirectory: Path,
    columns: Int,
): String {
    if (columns < WorkingDirectoryExpandedLabelMinimumColumns) return "cwd"
    val path = workingDirectory.toString()
    val maximumPathWidth = if (columns >= WorkingDirectoryWideLabelMinimumColumns) {
        WorkingDirectoryWidePathMaximumWidth
    } else {
        WorkingDirectoryPathMaximumWidth
    }
    val displayPath = if (path.terminalCellWidth() <= maximumPathWidth) {
        path
    } else {
        "…" + path.takeLastFittingTerminalWidth(maximumPathWidth - 1)
    }
    return displayPath
}

public const val SettingsLabel: String = "Settings"
private const val WorkingDirectoryExpandedLabelMinimumColumns: Int = 72
private const val WorkingDirectoryWideLabelMinimumColumns: Int = 112
private const val WorkingDirectoryPathMaximumWidth: Int = 16
private const val WorkingDirectoryWidePathMaximumWidth: Int = 28
