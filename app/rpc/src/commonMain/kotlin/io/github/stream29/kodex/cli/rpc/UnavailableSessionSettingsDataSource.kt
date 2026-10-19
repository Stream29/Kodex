package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.app.settings.contract.SessionSettingsConfiguration
import io.github.stream29.kodex.app.settings.contract.SessionSettingsDataSource
import io.github.stream29.kodex.app.settings.contract.SessionSettingsDataState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * No persisted target was captured. Never resolves a selected tab or supplies editable defaults.
 * Stateless across popup owners: disposal is a no-op and cannot affect another popup.
 */
public object UnavailableSessionSettingsDataSource : SessionSettingsDataSource {
    override val state: StateFlow<SessionSettingsDataState> =
        MutableStateFlow<SessionSettingsDataState>(SessionSettingsDataState.Unavailable).asStateFlow()

    override suspend fun tryUpdateConfiguration(
        expectedRevision: Long,
        configuration: SessionSettingsConfiguration,
    ): Boolean = false

    override suspend fun tryRenameSession(expectedRevision: Long, sessionName: String): Boolean = false

    override fun close(): Unit = Unit
}
