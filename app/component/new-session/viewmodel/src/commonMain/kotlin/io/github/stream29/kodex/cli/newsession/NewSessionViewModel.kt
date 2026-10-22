@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.newsession

import io.github.stream29.kodex.app.session.contract.*
import io.github.stream29.kodex.cli.rpc.RpcGlobalSettings
import io.github.stream29.kodex.cli.rpc.RpcSessionDraft
import io.github.stream29.kodex.cli.rpc.RpcSessionViews
import io.github.stream29.kodex.cli.rpc.createBoundRuntimeConfigurationViewModel
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path

public actual class DefaultNewSessionViewModelFactory actual constructor(
    private val views: RpcSessionViews,
    private val sessions: PersistedSessionViewModelRegistry,
    private val global: RpcGlobalSettings,
    private val scope: CoroutineScope,
) : NewSessionViewModelFactory {
    actual override fun create(arguments: NewSessionViewModelArguments): NewSessionViewModel =
        RpcNewSessionViewModel(arguments, views, sessions, global, scope)
}

public actual class RpcNewSessionViewModel internal actual constructor(
    arguments: NewSessionViewModelArguments,
    views: RpcSessionViews,
    private val sessions: PersistedSessionViewModelRegistry,
    global: RpcGlobalSettings,
    scope: CoroutineScope,
) : NewSessionViewModel {
    private val owner = Job(requireNotNull(scope.coroutineContext[Job]) {
        "New Session requires an explicit frontend owner."
    })
    private val local = CoroutineScope(scope.coroutineContext + owner)
    public actual val draft: RpcSessionDraft = RpcSessionDraft(arguments.initialWorkingDirectory, views, global, local)
    actual override val models: StateFlow<List<ModelInfo>> = global.models
    actual override val settings: StateFlow<KodexAgentSettings> = draft.settings
    actual override val runtimeConfiguration: io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel =
        createBoundRuntimeConfigurationViewModel(this, local)
    actual override val composer: io.github.stream29.kodex.app.agent.contract.ComposerViewModel = draft.composer
    init {
        owner.invokeOnCompletion { close() }
    }

    actual override suspend fun updateModel(model: OpenAiModelId): Unit =
        draft.editDefaults({ it.model }, { it.copy(model = model) })
    actual override suspend fun updateWorkingDirectory(workingDirectory: Path): Unit = draft.updateWorkingDirectory(workingDirectory)
    actual override suspend fun updateReasoningEffort(reasoningEffort: ReasoningEffort): Unit =
        draft.editDefaults({ it.reasoningEffort }, { it.copy(reasoningEffort = reasoningEffort) })
    actual override suspend fun updateServiceTier(serviceTier: ServiceTier): Unit =
        draft.editDefaults({ it.serviceTier }, { it.copy(serviceTier = serviceTier) })
    actual override suspend fun updateRequestUserInputMode(mode: RequestUserInputMode): Unit =
        draft.editDefaults({ it.requestUserInputMode }, { it.copy(requestUserInputMode = mode) })
    actual override suspend fun updateModelConfiguration(model: OpenAiModelId, reasoningEffort: ReasoningEffort, serviceTier: ServiceTier): Unit =
        draft.editDefaults(
            { Triple(it.model, it.reasoningEffort, it.serviceTier) },
            { it.copy(model = model, reasoningEffort = reasoningEffort, serviceTier = serviceTier) },
        )
    actual override suspend fun submit(expectedRevision: Long): PersistedSessionViewModel? {
        owner.ensureActive()
        return draft.submit(expectedRevision, sessions::open)
    }
    actual override fun close() { runtimeConfiguration.close(); draft.close(); owner.cancel() }
}
