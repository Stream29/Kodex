package io.github.stream29.kodex.app.settings

import io.github.stream29.kodex.app.settings.contract.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Navigation and child disposal only; each supplied child already owns its exact commands. */
public fun createSettingsViewModel(
    initialPage: SettingsPage,
    global: GlobalSettingsViewModel,
    session: SessionSettingsViewModel,
    newSession: NewSessionSettingsViewModel,
): SettingsViewModel = SettingsViewModelImpl(initialPage, global, session, newSession)

private class SettingsViewModelImpl(
    initialPage: SettingsPage,
    override val global: GlobalSettingsViewModel,
    override val session: SessionSettingsViewModel,
    override val newSession: NewSessionSettingsViewModel,
) : SettingsViewModel {
    private val page = MutableStateFlow(initialPage)
    override val selectedPage = page.asStateFlow()
    private var closed = false
    init { if (initialPage == SettingsPage.OpenAi) global.refreshUsage() }
    override fun selectPage(page: SettingsPage) {
        if (closed || this.page.value == page) return
        if (page != SettingsPage.OpenAi) global.dismissUsageReset()
        this.page.value = page
        if (page == SettingsPage.OpenAi) global.refreshUsage()
    }
    override fun close() {
        if (closed) return
        closed = true
        try { global.close() } finally {
            try { session.close() } finally { newSession.close() }
        }
    }
}
