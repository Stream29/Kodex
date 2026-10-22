package io.github.stream29.kodex.app.sessiontitlesettings

import kotlinx.coroutines.CoroutineScope

/** Creates a [SessionTitleSettingsViewModel] using the host's default model and admission ports. */
public expect fun createSessionTitleSettingsViewModel(
    dependencies: SessionTitleSettingsDependencies,
    ownerScope: CoroutineScope,
): SessionTitleSettingsViewModel
