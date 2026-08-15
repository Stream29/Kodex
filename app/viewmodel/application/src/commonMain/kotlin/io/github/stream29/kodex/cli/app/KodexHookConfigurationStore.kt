package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.cli.settings.KodexGlobalSettingsStore
import io.github.stream29.kodex.hook.contract.HookConfiguration
import io.github.stream29.kodex.hook.contract.HookConfigurationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Hook-specific atomic adapter over Kodex's global settings authority. */
internal class KodexHookConfigurationStore(
    private val settings: KodexGlobalSettingsStore,
    scope: CoroutineScope,
) : HookConfigurationStore {
    override val configuration: StateFlow<HookConfiguration> =
        settings.settings
            .map { snapshot -> snapshot.hooks }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = settings.settings.value.hooks,
            )

    override suspend fun update(
        transform: (HookConfiguration) -> HookConfiguration,
    ): HookConfiguration =
        settings.update { current ->
            current.copy(hooks = transform(current.hooks))
        }.hooks
}
