package io.github.stream29.kodex.app.settings

import io.github.stream29.kodex.app.settings.contract.GlobalSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.NewSessionSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.SessionSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.SettingsPage
import io.github.stream29.kodex.app.settings.contract.SettingsViewModel

/** Navigation and child disposal only; each supplied child already owns its exact commands. */
public expect fun createSettingsViewModel(
    initialPage: SettingsPage,
    global: GlobalSettingsViewModel,
    session: SessionSettingsViewModel,
    newSession: NewSessionSettingsViewModel,
): SettingsViewModel
