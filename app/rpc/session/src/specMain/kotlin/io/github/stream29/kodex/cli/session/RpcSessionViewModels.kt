@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.session

import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.session.contract.*
import io.github.stream29.kodex.cli.rpc.RpcSessionView
import io.github.stream29.kodex.cli.rpc.RpcSessionViews
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path
import kotlin.time.Instant

/** A stable tab owns successive read bindings, not a stable backend resource reference. */
public expect class RpcPersistedSessionViewModel : OwnedPersistedSessionViewModel {
    public val view: RpcSessionView
    override val models: StateFlow<List<ModelInfo>>
    override val rootAgent: StateFlow<AgentViewModel?>
    override val sessionIndex: Int
    override val settings: StateFlow<KodexAgentSettings>
    override val name: StateFlow<String>
    override val lifecycle: StateFlow<PersistedSessionLifecycleState>
    override suspend fun refresh()
    override suspend fun readCreatedAt(): Instant?
    override suspend fun readUpdatedAt(): Instant?
    override suspend fun fork(source: AgentViewModel, untilExclusive: Int, expectedGeneration: Long): Int
    override suspend fun fork(): Int
    override suspend fun rename(name: String)
    override suspend fun updateModel(model: OpenAiModelId)
    override suspend fun updateWorkingDirectory(workingDirectory: Path)
    override suspend fun updateReasoningEffort(reasoningEffort: ReasoningEffort)
    override suspend fun updateServiceTier(serviceTier: ServiceTier)
    override suspend fun updateRequestUserInputMode(mode: RequestUserInputMode)
    override suspend fun updateModelConfiguration(model: OpenAiModelId, reasoningEffort: ReasoningEffort, serviceTier: ServiceTier)
    override suspend fun shutdown()
    override fun close()
    override suspend fun join()
}


/** RPC adapter for the registry's exact-handle ownership port; borrows its frontend owner. */
public expect fun DefaultPersistedSessionViewModelRegistry(
    views: RpcSessionViews,
    models: StateFlow<List<ModelInfo>>,
    scope: CoroutineScope,
): DefaultPersistedSessionViewModelRegistry
