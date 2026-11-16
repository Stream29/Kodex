package io.github.stream29.kodex.cli.newsession

import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModelRegistry
import io.github.stream29.kodex.cli.rpc.RpcGlobalSettings
import io.github.stream29.kodex.cli.rpc.RpcSessionViews
import kotlinx.coroutines.CoroutineScope

/** Adapts the shared draft owner to RPC without exposing RPC in its component contract. */
public expect fun DefaultNewSessionViewModelFactory(
    views: RpcSessionViews,
    sessions: PersistedSessionViewModelRegistry = views,
    global: RpcGlobalSettings,
    scope: CoroutineScope,
): DefaultNewSessionViewModelFactory
