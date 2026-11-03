package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.rpc.models.FrontendErrorAdmission
import io.github.stream29.kodex.rpc.models.OAuthAuthorization
import io.github.stream29.kodex.rpc.models.OAuthTarget

/** Backend-owned effects used by the global service. */
public interface BackendFrontendEffects {
    public suspend fun startOAuth(target: OAuthTarget): OAuthAuthorization
    public suspend fun awaitOAuth(attemptId: Long)
    public suspend fun cancelOAuth(attemptId: Long)
    public suspend fun abandonPendingOAuth(attemptId: Long)
    public suspend fun removeAuthentication(source: KodexAuthSource)
    public suspend fun logoutMcp(serverName: String)
    public fun reportFrontendError(message: String?): FrontendErrorAdmission
}
