package io.github.stream29.kodex.cli.newsession

import io.github.stream29.kodex.app.session.contract.*
import io.github.stream29.kodex.cli.rpc.RpcSessionDraft
import io.github.stream29.kodex.cli.rpc.RpcSessionViews
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path

public const val DEFAULT_NEW_SESSION_NAME: String = "New Session"

public class DefaultNewSessionViewModelFactory(
    private val views: RpcSessionViews,
    private val sessions: PersistedSessionViewModelRegistry,
    private val models: StateFlow<List<ModelInfo>>,
    private val scope: CoroutineScope,
) : NewSessionViewModelFactory {
    override fun create(arguments: NewSessionViewModelArguments): NewSessionViewModel =
        RpcNewSessionViewModel(arguments, views, sessions, models, scope)
}

/** The displayed draft name is local; only an explicitly edited name is sent after allocation. */
public class RpcNewSessionViewModel internal constructor(
    arguments: NewSessionViewModelArguments,
    views: RpcSessionViews,
    private val sessions: PersistedSessionViewModelRegistry,
    override val models: StateFlow<List<ModelInfo>>,
    scope: CoroutineScope,
) : NewSessionViewModel {
    private val owner = Job(scope.coroutineContext[Job])
    private val local = CoroutineScope(scope.coroutineContext + owner)
    private val defaultName = arguments.defaultName
    public val draft: RpcSessionDraft = RpcSessionDraft(arguments.initialSettings.copy(threadName = ""), views)
    override val settings: StateFlow<KodexAgentSettings> = draft.settings
    override val composer: io.github.stream29.kodex.app.agent.contract.ComposerViewModel = draft.composer
    override val name: StateFlow<String> = settings.map { it.threadName.ifBlank { defaultName } }
        .stateIn(local, SharingStarted.Eagerly, defaultName)
    override suspend fun rename(name: String) {
        val normalized = name.trim()
        require(normalized.isNotBlank())
        draft.edit { it.copy(threadName = normalized) }
    }
    override suspend fun clearExplicitThreadName(): Unit = draft.clearExplicitThreadName()
    override suspend fun updateModel(model: OpenAiModelId): Unit = draft.edit { it.copy(model = model) }
    override suspend fun updateWorkingDirectory(workingDirectory: Path): Unit = draft.edit { it.copy(cwd = workingDirectory) }
    override suspend fun updateReasoningEffort(reasoningEffort: ReasoningEffort): Unit =
        draft.edit { it.copy(reasoning = it.reasoning.copy(effort = reasoningEffort)) }
    override suspend fun updateServiceTier(serviceTier: ServiceTier): Unit = draft.edit { it.copy(serviceTier = serviceTier) }
    override suspend fun updateRequestUserInputMode(mode: RequestUserInputMode): Unit = draft.edit { it.copy(requestUserInputMode = mode) }
    override suspend fun updateModelConfiguration(model: OpenAiModelId, reasoningEffort: ReasoningEffort, serviceTier: ServiceTier): Unit =
        draft.edit { it.copy(model = model, reasoning = it.reasoning.copy(effort = reasoningEffort), serviceTier = serviceTier) }
    override suspend fun materialize(): PersistedSessionViewModel {
        owner.ensureActive()
        return sessions.open(draft.materialize().index)
    }
    override fun close() { draft.close(); owner.cancel() }
}
