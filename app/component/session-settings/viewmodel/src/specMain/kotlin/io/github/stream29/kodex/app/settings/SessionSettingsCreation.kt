package io.github.stream29.kodex.app.settings

import io.github.stream29.kodex.app.settings.contract.SessionSettingsDependencies
import io.github.stream29.kodex.app.settings.contract.SessionSettingsViewModel
import kotlinx.coroutines.CoroutineScope

/** Creates a [SessionSettingsViewModel] that owns [SessionSettingsDependencies.source]. */
public expect fun createSessionSettingsViewModel(
    dependencies: SessionSettingsDependencies,
    ownerScope: CoroutineScope,
): SessionSettingsViewModel
