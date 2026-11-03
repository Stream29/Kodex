package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.rpc.models.FrontendErrorAdmission
import io.github.stream29.kodex.rpc.models.OAuthAuthorization
import io.github.stream29.kodex.rpc.models.OAuthTarget

internal class BackendFrontendEffectsImpl(
    private val owned: BackendOwnedOAuth,
    private val oauth: BackendOAuth,
    private val errors: FrontendErrorQueue,
) : BackendFrontendEffects {
    override suspend fun startOAuth(target: OAuthTarget): OAuthAuthorization = owned.start(target)
    override suspend fun awaitOAuth(attemptId: Long) = owned.await(attemptId)
    override suspend fun cancelOAuth(attemptId: Long) = owned.cancel(attemptId)
    override suspend fun abandonPendingOAuth(attemptId: Long) = owned.abandonPending(attemptId)

    override suspend fun removeAuthentication(source: KodexAuthSource) {
        owned.cancelTarget(OAuthTarget.OpenAi(source))
        oauth.removeAuthentication(source)
    }

    override suspend fun logoutMcp(serverName: String) = oauth.logoutMcpServer(serverName)

    override fun reportFrontendError(message: String?): FrontendErrorAdmission = when (errors.admit(message)) {
        FrontendErrorQueue.Admission.ADMITTED -> FrontendErrorAdmission.Admitted
        FrontendErrorQueue.Admission.OVERLOADED -> FrontendErrorAdmission.RejectedOverload
        FrontendErrorQueue.Admission.CLOSED -> error("Frontend error owner is closed.")
    }

    suspend fun closeAndJoin() {
        closeBackendResources(null, { owned.closeAndJoin() }, { errors.closeAndJoin() })
    }
}
