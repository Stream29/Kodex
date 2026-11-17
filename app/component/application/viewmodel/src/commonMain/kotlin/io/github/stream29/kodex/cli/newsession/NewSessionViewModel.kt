@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.newsession

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.app.agent.contract.AgentSettingsViewModel
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel
import io.github.stream29.kodex.app.session.contract.*
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path

public actual class DefaultNewSessionViewModelFactory actual constructor(
    private val createDraft: (NewSessionViewModelArguments, CoroutineScope) -> NewSessionDraftPort,
    private val models: StateFlow<List<ModelInfo>>,
    private val sessions: PersistedSessionViewModelRegistry,
    private val createRuntimeConfiguration: (AgentSettingsViewModel, CoroutineScope) -> RuntimeConfigurationViewModel,
    private val scope: CoroutineScope,
) : NewSessionViewModelFactory {
    actual override fun create(arguments: NewSessionViewModelArguments): NewSessionViewModel =
        RpcNewSessionViewModel(arguments, createDraft, models, sessions, createRuntimeConfiguration, scope)
}

private class RpcNewSessionViewModel(
    arguments: NewSessionViewModelArguments,
    createDraft: (NewSessionViewModelArguments, CoroutineScope) -> NewSessionDraftPort,
    override val models: StateFlow<List<ModelInfo>>,
    private val sessions: PersistedSessionViewModelRegistry,
    createRuntimeConfiguration: (AgentSettingsViewModel, CoroutineScope) -> RuntimeConfigurationViewModel,
    scope: CoroutineScope,
) : NewSessionViewModel {
    private val owner = Job(requireNotNull(scope.coroutineContext[Job]) {
        "New Session requires an explicit frontend owner."
    })
    private val local = CoroutineScope(scope.coroutineContext + owner)
    private val draft: NewSessionDraftPort = createDraft(arguments, local)
    override val settings: StateFlow<KodexAgentSettings> = draft.settings
    override val runtimeConfiguration: RuntimeConfigurationViewModel =
        createRuntimeConfiguration(this, local)
    override val composer: ComposerViewModel = draft.composer
    init {
        owner.invokeOnCompletion { close() }
    }

    override suspend fun updateModel(model: OpenAiModelId): Unit =
        draft.editDefaults({ it.model }, { it.copy(model = model) })
    override suspend fun updateWorkingDirectory(workingDirectory: Path): Unit = draft.updateWorkingDirectory(workingDirectory)
    override suspend fun updateReasoningEffort(reasoningEffort: ReasoningEffort): Unit =
        draft.editDefaults({ it.reasoningEffort }, { it.copy(reasoningEffort = reasoningEffort) })
    override suspend fun updateServiceTier(serviceTier: ServiceTier): Unit =
        draft.editDefaults({ it.serviceTier }, { it.copy(serviceTier = serviceTier) })
    override suspend fun updateRequestUserInputMode(mode: RequestUserInputMode): Unit =
        draft.editDefaults({ it.requestUserInputMode }, { it.copy(requestUserInputMode = mode) })
    override suspend fun updateModelConfiguration(model: OpenAiModelId, reasoningEffort: ReasoningEffort, serviceTier: ServiceTier): Unit =
        draft.editDefaults(
            { Triple(it.model, it.reasoningEffort, it.serviceTier) },
            { it.copy(model = model, reasoningEffort = reasoningEffort, serviceTier = serviceTier) },
        )
    override suspend fun submit(expectedRevision: Long): PersistedSessionViewModel? {
        owner.ensureActive()
        return draft.submit(expectedRevision, sessions::open)
    }
    override fun close() { runtimeConfiguration.close(); draft.close(); owner.cancel() }
}
