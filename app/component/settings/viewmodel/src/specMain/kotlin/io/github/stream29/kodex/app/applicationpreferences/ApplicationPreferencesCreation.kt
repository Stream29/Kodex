package io.github.stream29.kodex.app.applicationpreferences

import kotlinx.coroutines.CoroutineScope

/** Creates an observing [ApplicationPreferencesViewModel] without initializing application widths. */
public expect fun createApplicationPreferencesViewModel(
    dependencies: ApplicationPreferencesDependencies,
    ownerScope: CoroutineScope,
): ApplicationPreferencesViewModel
