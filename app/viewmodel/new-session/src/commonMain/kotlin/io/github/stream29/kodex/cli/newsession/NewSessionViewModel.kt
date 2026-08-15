package io.github.stream29.kodex.cli.newsession

import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.app.agent.contract.ComposerViewModelFactory
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.app.session.contract.NewSessionViewModelArguments
import io.github.stream29.kodex.app.session.contract.NewSessionViewModelFactory
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModelRegistry
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.AgentMode
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam

/**
 * One process-local Session draft.
 *
 * Settings and name edits share [commandMutex] with [materialize], so the
 * persisted root receives one exact draft snapshot. The composer uses its own
 * revision CAS; it is cleared only after both creation and initial submission
 * have succeeded.
 */
internal class NewSessionViewModelImpl(
    arguments: NewSessionViewModelArguments,
    private val sessions: PersistedSessionViewModelRegistry,
    composerFactory: ComposerViewModelFactory,
    override val models: StateFlow<List<ModelInfo>>,
) : NewSessionViewModel {
    private val commandMutex = Mutex()
    private val defaultName = arguments.defaultName.trim()
    private val mutableName = MutableStateFlow(defaultName)
    private val mutableSettings = MutableStateFlow(
        arguments.initialSettings.copy(threadName = defaultName),
    )
    private var explicitThreadName = false
    private var consumed = false
    private var closed = false

    override val composer: ComposerViewModel = composerFactory.create()
    override val name: StateFlow<String> = mutableName.asStateFlow()
    override val settings: StateFlow<KodexAgentSettings> = mutableSettings.asStateFlow()

    override suspend fun rename(name: String) {
        val normalized = name.trim()
        require(normalized.isNotEmpty()) { "A Session name cannot be blank." }
        commandMutex.withLock {
            ensureEditable()
            explicitThreadName = true
            mutableName.value = normalized
            mutableSettings.value = mutableSettings.value.copy(threadName = normalized)
        }
    }

    override suspend fun clearExplicitThreadName() {
        commandMutex.withLock {
            ensureEditable()
            explicitThreadName = false
            mutableName.value = defaultName
            mutableSettings.value = mutableSettings.value.copy(threadName = defaultName)
        }
    }

    override suspend fun updateModel(model: OpenAiModelId) {
        updateSettings { current -> current.copy(model = model) }
    }

    override suspend fun updateWorkingDirectory(workingDirectory: Path) {
        updateSettings { current -> current.copy(cwd = workingDirectory) }
    }

    override suspend fun updateReasoningEffort(reasoningEffort: ReasoningEffort) {
        updateSettings { current ->
            current.copy(reasoning = current.reasoning.copy(effort = reasoningEffort))
        }
    }

    override suspend fun updateServiceTier(serviceTier: ServiceTier) {
        updateSettings { current -> current.copy(serviceTier = serviceTier) }
    }

    override suspend fun updateAgentMode(agentMode: AgentMode) {
        updateSettings { current -> current.copy(agentMode = agentMode) }
    }

    override suspend fun updateRequestUserInputMode(mode: RequestUserInputMode) {
        updateSettings { current -> current.copy(requestUserInputMode = mode) }
    }

    override suspend fun updateModelConfiguration(
        model: OpenAiModelId,
        reasoningEffort: ReasoningEffort,
        serviceTier: ServiceTier,
    ) {
        updateSettings { current ->
            current.copy(
                model = model,
                reasoning = current.reasoning.copy(effort = reasoningEffort),
                serviceTier = serviceTier,
            )
        }
    }

    override suspend fun materialize(): PersistedSessionViewModel = commandMutex.withLock {
        ensureEditable()
        val capturedSettings = mutableSettings.value.copy(threadName = mutableName.value)
        val capturedComposer = composer.state.value
        val initialText = capturedComposer.text.trim()
        var created: PersistedSessionViewModel? = null
        try {
            created = sessions.create { sessionIndex ->
                val effectiveName = if (explicitThreadName) {
                    capturedSettings.threadName
                } else {
                    defaultNameForIndex(sessionIndex)
                }
                capturedSettings.copy(threadName = effectiveName)
            }
            if (initialText.isNotEmpty()) {
                created.rootAgent.submit(listOf(ContentItem.InputText(initialText)))
            }
            check(composer.clear(capturedComposer.revision)) {
                "A New Session composer changed while materialization was serialized."
            }
            consumed = true
            created
        } catch (failure: CancellationException) {
            created?.let { sessions.rollbackCreated(it.sessionIndex) }
            throw failure
        } catch (failure: Throwable) {
            created?.let { sessions.rollbackCreated(it.sessionIndex) }
            throw failure
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        composer.close()
    }

    private suspend fun updateSettings(
        transform: (KodexAgentSettings) -> KodexAgentSettings,
    ) {
        commandMutex.withLock {
            ensureEditable()
            val current = mutableSettings.value
            val updated = transform(current)
            if (updated != current) mutableSettings.value = updated
        }
    }

    private fun defaultNameForIndex(sessionIndex: Int): String = "Session $sessionIndex"

    private fun ensureEditable() {
        check(!closed) { "New Session ViewModel is closed." }
        check(!consumed) { "New Session ViewModel was already materialized." }
    }
}

@Factory(binds = [NewSessionViewModelFactory::class])
public class DefaultNewSessionViewModelFactory(
    @InjectedParam private val sessions: PersistedSessionViewModelRegistry,
    @InjectedParam private val composerFactory: ComposerViewModelFactory,
    @InjectedParam private val models: StateFlow<List<ModelInfo>>,
) : NewSessionViewModelFactory {
    override fun create(arguments: NewSessionViewModelArguments): NewSessionViewModel =
        NewSessionViewModelImpl(
            arguments = arguments,
            sessions = sessions,
            composerFactory = composerFactory,
            models = models,
        )
}

public const val DEFAULT_NEW_SESSION_NAME: String = "New Session"
