package io.github.stream29.kodex.app.authenticationsettings

import kotlinx.coroutines.CoroutineScope

/** Observe shared summaries without creating an OAuth client or executing an initial command. */
public expect fun createAuthenticationSettingsViewModel(
    dependencies: AuthenticationSettingsDependencies,
    ownerScope: CoroutineScope,
): AuthenticationSettingsViewModel
