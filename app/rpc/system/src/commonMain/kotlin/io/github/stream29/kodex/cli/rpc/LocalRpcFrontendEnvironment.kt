package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.app.contextsourcesettings.ContextSourcePathPolicy
import io.github.stream29.kodex.cli.auth.KodexAuthLoginAttempt
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.models.OAuthTarget
import io.github.stream29.kodex.utils.osenvironment.requireUserHomeDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.io.files.Path

public actual val LocalRpcFrontendEnvironment: RpcFrontendEnvironment get() = object : RpcFrontendEnvironment {
    override val contextPaths: ContextSourcePathPolicy = FrontendContextPathPolicy()
    override val oauthCallbacks: RpcOAuthCallbackProvider = RpcOAuthCallbackProvider { scope, target, redirect ->
        val candidates = when (target) {
            is OAuthTarget.OpenAi -> listOf("http://localhost:1455/auth/callback", "http://localhost:1457/auth/callback")
            is OAuthTarget.Mcp -> listOf(requireNotNull(redirect))
        }
        startLoopback(scope, candidates)
    }
}

public actual fun RpcGlobalEditor(
    global: RpcGlobalSettings,
    rpc: GlobalRpc,
    applicationScope: CoroutineScope,
): RpcGlobalEditor = RpcGlobalEditor(global, rpc, applicationScope, LocalRpcFrontendEnvironment)

public actual suspend fun startRpcOAuth(
    rpc: GlobalRpc,
    target: OAuthTarget,
    scope: CoroutineScope,
    mcpRedirectUri: String?,
): KodexAuthLoginAttempt = startRpcOAuth(rpc, target, scope, LocalRpcFrontendEnvironment.oauthCallbacks, mcpRedirectUri)

private class FrontendContextPathPolicy : ContextSourcePathPolicy {
    private val home: Path by lazy { Path(requireUserHomeDirectory()) }
    override val builtInNormalizedPaths: Set<String>
        get() = setOf(".agents", ".kodex", ".codex").map { Path(home, it).toString() }.toSet()

    override fun normalize(path: String): String? {
        if (path.isBlank() || '$' in path) return null
        val capturedHome = home
        return when {
            path == "~" -> capturedHome
            path.startsWith("~/") -> Path(capturedHome, path.substring(2))
            else -> Path(path).takeIf { it.isAbsolute }
        }?.toString()
    }
}
