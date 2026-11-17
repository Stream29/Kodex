package io.github.stream29.kodex.app.contextsourcesettings

import kotlinx.coroutines.CoroutineScope

/** Creates a [ContextSourceSettingsViewModel] from the host's source and write ports. */
public expect fun createContextSourceSettingsViewModel(
    dependencies: ContextSourceSettingsDependencies,
    ownerScope: CoroutineScope,
): ContextSourceSettingsViewModel
