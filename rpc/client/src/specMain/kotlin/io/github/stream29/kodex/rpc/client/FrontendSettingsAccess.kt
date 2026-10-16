package io.github.stream29.kodex.rpc.client

import io.github.stream29.kodex.rpc.models.CliFrontendSettings
import kotlinx.coroutines.flow.StateFlow

/** Frontend preferences access, without filesystem ownership, path or reload-management capability. */
public interface FrontendSettingsAccess {
    public val settings: StateFlow<CliFrontendSettings>
    /**
     * Submits one captured edit through this access's authority. Transmission failures are not
     * comparison failures and never cause automatic command replay. Accepted writes can outlive
     * the caller's wait. Implementations preserve unrelated concurrently changed fields.
     */
    public suspend fun update(transform: (CliFrontendSettings) -> CliFrontendSettings): CliFrontendSettings
}
