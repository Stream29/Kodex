package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import io.github.stream29.kodex.app.application.contract.SidebarSettingsViewModel
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel
import io.github.stream29.kodex.cli.settings.SidebarContent
import io.github.stream29.kodex.openai.KodexAgentSettings

@Composable
public actual fun ApplicationDraftStatusContent(
    settings: KodexAgentSettings, runtimeConfiguration: RuntimeConfigurationViewModel,
    renderer: AgentStatusRenderer, onBrowseWorkingDirectory: () -> Unit, onOpenSettings: () -> Unit,
) {
    renderer.Frame(
        regular = {
            renderer.Configuration(runtimeConfiguration)
            renderer.Directory(settings.cwd, onBrowseWorkingDirectory)
        },
        settings = { renderer.Settings(onOpenSettings) },
    )
}

public actual suspend fun selectApplicationSidebarContent(
    owner: SidebarSettingsViewModel, side: SessionSidebarSide, content: SidebarContent,
) {
    when (side) {
        SessionSidebarSide.Left -> owner.selectLeft(content)
        SessionSidebarSide.Right -> owner.selectRight(content)
    }
}

public actual suspend fun resizeApplicationSidebar(owner: SidebarSettingsViewModel, side: SessionSidebarSide, columns: Int) {
    when (side) {
        SessionSidebarSide.Left -> owner.resizeLeft(columns)
        SessionSidebarSide.Right -> owner.resizeRight(columns)
    }
}
