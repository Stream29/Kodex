@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.openai.client.OpenAiClient
import io.github.stream29.kodex.openai.client.OpenAiLoginClient
import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import io.github.stream29.kodex.openai.client.contract.OpenAiClient as Client
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient as LoginClient
import io.github.stream29.kodex.rpc.contract.AgentRuntimeRpc
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.models.BackendSettings
import io.github.stream29.kodex.rpc.models.Notification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.io.files.Path
import kotlinx.rpc.RpcServer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Owns a complete backend graph without selecting a CLI or running Home migration.
 *
 * Callers must provide a prepared Home. The OpenAI login/client factories are injectable test
 * seams, not frontend services; their returned clients are owned here and closed after their
 * consumers. Settings, authentication, model and global-service initialization does not create
 * `kodex.db` on an empty Home.
 *
 * @throws CancellationException if the calling coroutine is cancelled; acquired resources are
 * cleaned before cancellation escapes.
 * @throws Throwable if resource creation, graph initialization or [block] fails, or cleanup fails
 * after normal return.
 */
public expect suspend fun <R> withBackendServices(
    home: Path,
    codexHome: Path,
    agentsHome: Path,
    defaults: BackendSettings = defaultBackendSettings(),
    createLoginClient: () -> LoginClient = { OpenAiLoginClient() },
    createClient: (OpenAiAuthStore) -> Client = { OpenAiClient(it) },
    renewBackend: suspend () -> Unit = {},
    backendRenewalInterval: () -> Duration = { 20.seconds },
    checkAdmission: () -> Unit = {},
    block: suspend CoroutineScope.(BackendServices) -> R,
): R

/**
 * Registered exactly once per shared connection; the graph remains owned by [withBackendServices].
 *
 * This non-owning view of the complete backend service graph must not outlive its
 * [withBackendServices] callback. [register] installs the Global and AgentRuntime RPC services;
 * call it once per shared RPC connection.
 */
public expect class BackendServices {
    public val global: GlobalRpc
    public val runtime: AgentRuntimeRpc
    public val settings: BackendGlobalState
    public val notifications: Flow<Notification>

    /** Closes all open Sessions (draining accepted operations) before network teardown. */
    public suspend fun shutdownSessions(): Unit

    public fun register(server: RpcServer): Unit

    /** [allowAgentSql] must reflect server-side connection admission, not a client claim. */
    public fun register(server: RpcServer, allowAgentSql: Boolean): Unit
}
