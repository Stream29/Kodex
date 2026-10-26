package io.github.stream29.kodex.cli.newsession

import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModelRegistry
import io.github.stream29.kodex.cli.rpc.RpcGlobalSettings
import io.github.stream29.kodex.cli.rpc.RpcSessionViews
import io.github.stream29.kodex.cli.rpc.RpcSessionDraft
import io.github.stream29.kodex.cli.rpc.createBoundRuntimeConfigurationViewModel
import kotlinx.coroutines.CoroutineScope

public actual fun DefaultNewSessionViewModelFactory(
    views: RpcSessionViews,
    sessions: PersistedSessionViewModelRegistry,
    global: RpcGlobalSettings,
    scope: CoroutineScope,
): DefaultNewSessionViewModelFactory = DefaultNewSessionViewModelFactory(
    createDraft = { arguments, owner -> RpcSessionDraft(arguments.initialWorkingDirectory, views, global, owner) },
    models = global.models,
    sessions = sessions,
    createRuntimeConfiguration = ::createBoundRuntimeConfigurationViewModel,
    scope = scope,
)
