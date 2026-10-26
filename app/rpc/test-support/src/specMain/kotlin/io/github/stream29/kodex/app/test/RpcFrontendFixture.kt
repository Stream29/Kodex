@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.app.test

import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.cli.newsession.DefaultNewSessionViewModelFactory
import io.github.stream29.kodex.cli.rpc.RpcGlobalSettings
import io.github.stream29.kodex.cli.rpc.RpcServices
import io.github.stream29.kodex.cli.rpc.RpcSessionViews
import io.github.stream29.kodex.cli.session.DefaultPersistedSessionViewModelRegistry
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path
import kotlin.coroutines.CoroutineContext

/** Complete real backend, JSON transport and frontend views, with only external APIs mocked. */
public expect class RpcFrontendFixture : CoroutineScope {
    override val coroutineContext: CoroutineContext
    public val root: Path
    public val services: RpcServices
    public val views: RpcSessionViews
    public val global: RpcGlobalSettings
    public val sessions: DefaultPersistedSessionViewModelRegistry
    public val drafts: DefaultNewSessionViewModelFactory
    public val models: StateFlow<List<ModelInfo>>
    public val failures: StateFlow<List<Throwable>>
    public suspend fun create(name: String = "Test Session"): PersistedSessionViewModel
    public fun draft(): NewSessionViewModel
    public suspend fun closeAndJoin()
}

public expect fun testSettings(name: String = "", cwd: Path = Path(".")): KodexAgentSettings

/**
 * Runs the backend on Default. A renderer test may supply its single interaction dispatcher
 * for frontend views, so Compose state/commands and drawing do not execute concurrently.
 * Null preserves the existing backend-context frontend used by non-renderer fixtures.
 * The fixture owns all frontend jobs until [RpcFrontendFixture.closeAndJoin].
 */
public expect suspend fun startRpcFrontendFixture(
    scope: CoroutineScope,
    response: suspend FlowCollector<ResponsesStreamEvent>.() -> Unit = { testAnswer() },
    seed: suspend CoroutineScope.(Path) -> Unit = {},
    frontendDispatcher: CoroutineDispatcher? = null,
): RpcFrontendFixture

public expect suspend fun withRpcFrontend(
    response: suspend FlowCollector<ResponsesStreamEvent>.() -> Unit = { testAnswer() },
    block: suspend RpcFrontendFixture.() -> Unit,
)

public expect suspend fun FlowCollector<ResponsesStreamEvent>.testAnswer()
public expect suspend fun deleteTestDirectory(path: Path)

/** Offline fixture seeding finishes before the RPC backend acquires this Session. */
public expect suspend fun CoroutineScope.seedTestHistory(
    home: Path, name: String, events: Map<Int, StableIndexEvent>,
): Int
