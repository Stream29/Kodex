package io.github.stream29.kodex.app.hooksettings

import kotlinx.coroutines.CoroutineScope

/** Creates a [HookSettingsViewModel] using host-owned list and write ports. */
public expect fun createHookSettingsViewModel(
    dependencies: HookSettingsDependencies,
    ownerScope: CoroutineScope,
): HookSettingsViewModel
