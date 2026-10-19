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

public class DefaultNewSessionViewModelFactory(
    private val views: RpcSessionViews,
    private val sessions: PersistedSessionViewModelRegistry,
    private val global: RpcGlobalSettings,
    private val scope: CoroutineScope,
) : NewSessionViewModelFactory {
    override fun create(arguments: NewSessionViewModelArguments): NewSessionViewModel =
        RpcNewSessionViewModel(arguments, views, sessions, global, scope)
}

/** Reusable frontend child; only cwd/Composer are local, model configuration edits shared defaults. */
public class RpcNewSessionViewModel internal constructor(
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
    public val draft: RpcSessionDraft = RpcSessionDraft(arguments.initialWorkingDirectory, views, global, local)
    override val models: StateFlow<List<ModelInfo>> = global.models
    override val settings: StateFlow<KodexAgentSettings> = draft.settings
    override val runtimeConfiguration: io.github.stream29.kodex.app.runtimeconfiguration.RuntimeConfigurationViewModel =
        createBoundRuntimeConfigurationViewModel(this, local)
    override val composer: io.github.stream29.kodex.app.agent.contract.ComposerViewModel = draft.composer
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
