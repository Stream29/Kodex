package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.application.contract.SidebarSettingsViewModel
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel
import io.github.stream29.kodex.cli.settings.SidebarContent
import io.github.stream29.kodex.openai.KodexAgentSettings

/** Shared draft status order, borrowing the exact configuration child; geometry stays in the renderer. */
@Composable
public expect fun ApplicationDraftStatusContent(
    settings: KodexAgentSettings, runtimeConfiguration: RuntimeConfigurationViewModel,
    renderer: AgentStatusRenderer, onBrowseWorkingDirectory: () -> Unit, onOpenSettings: () -> Unit,
): Unit

/** Uses the captured preference owner/side and propagates failure or caller cancellation without retries. */
public expect suspend fun selectApplicationSidebarContent(
    owner: SidebarSettingsViewModel, side: SessionSidebarSide, content: SidebarContent,
): Unit

/** The argument is logical columns, never an unconverted renderer pixel width. */
public expect suspend fun resizeApplicationSidebar(
    owner: SidebarSettingsViewModel, side: SessionSidebarSide, columns: Int,
): Unit
