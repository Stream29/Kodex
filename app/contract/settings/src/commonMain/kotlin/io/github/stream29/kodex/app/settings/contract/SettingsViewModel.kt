package io.github.stream29.kodex.app.settings.contract

import io.github.stream29.kodex.app.session.contract.SessionViewModel
import kotlinx.coroutines.flow.StateFlow

/** Stable Settings navigation destinations. */
public enum class SettingsPage {
    Global,
    Session,
    NewSession,
}

/**
 * Root owner for one Settings popup.
 *
 * All three child handles are stable for this ViewModel's lifetime. The
 * Session child remains bound to the target captured when the popup opened.
 */
public interface SettingsViewModel : AutoCloseable {
    public val selectedPage: StateFlow<SettingsPage>
    public val global: GlobalSettingsViewModel
    public val session: SessionSettingsViewModel
    public val newSession: NewSessionSettingsViewModel

    public fun selectPage(page: SettingsPage): Unit

    override fun close(): Unit
}

/** Exact inputs for one independently owned Settings popup child. */
public data class SettingsViewModelArguments(
    public val target: SessionViewModel,
    public val initialPage: SettingsPage,
)

/**
 * Creates one Settings hierarchy bound to the captured Session target.
 *
 * Login is a separately scoped child because it has a shorter lifetime than
 * the Settings popup and is opened only on explicit user intent.
 */
public fun interface SettingsViewModelFactory {
    public fun create(arguments: SettingsViewModelArguments): SettingsViewModel
}
