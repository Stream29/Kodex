@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.app.settings.contract.SessionSettingsConfiguration
import io.github.stream29.kodex.app.settings.contract.SessionSettingsDataSource
import io.github.stream29.kodex.app.settings.contract.SessionSettingsDataState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

public actual object UnavailableSessionSettingsDataSource : SessionSettingsDataSource {
    actual override val state: StateFlow<SessionSettingsDataState> =
        MutableStateFlow<SessionSettingsDataState>(SessionSettingsDataState.Unavailable).asStateFlow()

    actual override suspend fun tryUpdateConfiguration(
        expectedRevision: Long,
        configuration: SessionSettingsConfiguration,
    ): Boolean = false

    actual override suspend fun tryRenameSession(expectedRevision: Long, sessionName: String): Boolean = false

    actual override fun close(): Unit = Unit
}
