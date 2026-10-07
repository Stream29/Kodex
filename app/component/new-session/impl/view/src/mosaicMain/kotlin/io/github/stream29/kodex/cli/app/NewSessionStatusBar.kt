package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfiguration
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationDropdowns
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationStatusItemsWithoutSpacing
import io.github.stream29.kodex.cli.runtimeconfiguration.runtimeConfigurationButtonWidths
import io.github.stream29.kodex.cli.components.SettingsLabel
import io.github.stream29.kodex.cli.components.SettingsStatusButton
import io.github.stream29.kodex.cli.components.StatusBarLayout
import io.github.stream29.kodex.cli.components.WorkingDirectoryStatusButton
import io.github.stream29.kodex.cli.components.buttonWidth
import io.github.stream29.kodex.cli.components.statusBarLayoutPlan
import io.github.stream29.kodex.cli.components.statusBarWidth
import io.github.stream29.kodex.cli.components.workingDirectoryStatusLabel
import io.github.stream29.kodex.openai.KodexAgentSettings

/** Borrows the draft's existing configuration child; owns no draft or registry state. */
@Composable
public fun NewSessionStatusBar(
    columns: Int,
    settings: KodexAgentSettings,
    runtimeConfiguration: RuntimeConfigurationViewModel,
    dropdowns: RuntimeConfigurationDropdowns,
    onBrowseWorkingDirectory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    StatusBarLayout(
        columns = columns,
        regularContent = {
            RuntimeConfigurationStatusItemsWithoutSpacing(runtimeConfiguration, dropdowns)
            WorkingDirectoryStatusButton(
                columns = columns,
                workingDirectory = settings.cwd,
                enabled = true,
                onBrowse = onBrowseWorkingDirectory,
            )
        },
        settingsContent = {
            SettingsStatusButton(onOpenSettings)
        },
    )
}

public fun newSessionStatusBarRows(
    columns: Int,
    settings: KodexAgentSettings,
): Int {
    val configuration = RuntimeConfiguration(
        model = settings.model,
        reasoning = settings.reasoning.effort,
        tier = settings.serviceTier,
        requestUserInputMode = settings.requestUserInputMode,
    )
    val widths = runtimeConfigurationButtonWidths(configuration) +
        buttonWidth(workingDirectoryStatusLabel(settings.cwd, columns))
    return statusBarLayoutPlan(
        width = statusBarWidth(columns),
        itemWidths = widths,
        settingsWidth = buttonWidth(SettingsLabel),
    ).rowCount
}
