package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.app.contextsourcesettings.ContextSourcePathPolicy
import io.github.stream29.kodex.rpc.models.OAuthTarget
import kotlinx.coroutines.CoroutineScope

/** Host capabilities borrowed by an editor, independent of its RPC connection. */
public interface RpcFrontendEnvironment {
    public val contextPaths: ContextSourcePathPolicy
    public val oauthCallbacks: RpcOAuthCallbackProvider
}

/** Selects a provider-supported redirect and owns only this attempt's callback listener. */
public fun interface RpcOAuthCallbackProvider {
    public suspend fun listen(
        scope: CoroutineScope,
        target: OAuthTarget,
        mcpRedirectUri: String?,
    ): OAuthCallbackListener
}

/** Transient callback data must not be logged, persisted, or shared across attempts. */
public interface OAuthCallbackListener {
    public val redirectUri: String
    /** Admit only one callback with exactly this unique state; reject duplicates and unrelated callbacks. */
    public fun expectState(value: String)
    public suspend fun awaitCallback(): String
    /** Releases this listener without closing the borrowed owner or backend. */
    public suspend fun close()
}
