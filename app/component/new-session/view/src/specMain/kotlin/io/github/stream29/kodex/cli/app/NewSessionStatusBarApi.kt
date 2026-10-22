package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationDropdowns
import io.github.stream29.kodex.openai.KodexAgentSettings

/** Borrows the draft's existing configuration child; owns no draft or registry state. */
@Composable
public expect fun NewSessionStatusBar(
    columns: Int,
    settings: KodexAgentSettings,
    runtimeConfiguration: RuntimeConfigurationViewModel,
    dropdowns: RuntimeConfigurationDropdowns,
    onBrowseWorkingDirectory: () -> Unit,
    onOpenSettings: () -> Unit,
): Unit

public expect fun newSessionStatusBarRows(
    columns: Int,
    settings: KodexAgentSettings,
): Int
