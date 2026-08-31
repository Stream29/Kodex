package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.app.application.contract.SidebarSettingsViewModel
import io.github.stream29.kodex.cli.settings.KodexGlobalSettingsStore
import io.github.stream29.kodex.cli.settings.SidebarContent
import io.github.stream29.kodex.cli.settings.SidebarSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal class SidebarSettingsViewModelImpl(
    private val globalSettings: KodexGlobalSettingsStore,
    scope: CoroutineScope,
) : SidebarSettingsViewModel {
    override val state: StateFlow<SidebarSettings> =
        globalSettings.settings
            .map { settings -> settings.sidebars }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = globalSettings.settings.value.sidebars,
            )

    override suspend fun selectLeft(content: SidebarContent) {
        globalSettings.update { settings ->
            settings.copy(sidebars = settings.sidebars.copy(left = content))
        }
    }

    override suspend fun selectRight(content: SidebarContent) {
        globalSettings.update { settings ->
            settings.copy(sidebars = settings.sidebars.copy(right = content))
        }
    }

    override suspend fun resizeLeft(columns: Int) {
        globalSettings.update { settings ->
            settings.copy(sidebars = settings.sidebars.copy(leftWidth = columns))
        }
    }

    override suspend fun resizeRight(columns: Int) {
        globalSettings.update { settings ->
            settings.copy(sidebars = settings.sidebars.copy(rightWidth = columns))
        }
    }
}
