package io.github.stream29.kodex.app.settings

import io.github.stream29.kodex.app.settings.contract.NewSessionDefaultsDependencies
import io.github.stream29.kodex.app.settings.contract.NewSessionSettingsViewModel
import kotlinx.coroutines.CoroutineScope

/** Creates a [NewSessionSettingsViewModel] with synchronously projected borrowed sources. */
public expect fun createNewSessionDefaultsViewModel(
    dependencies: NewSessionDefaultsDependencies,
    ownerScope: CoroutineScope,
): NewSessionSettingsViewModel
