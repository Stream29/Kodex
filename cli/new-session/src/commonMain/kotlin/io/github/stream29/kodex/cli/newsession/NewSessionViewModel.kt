package io.github.stream29.kodex.cli.newsession

import io.github.stream29.kodex.cli.session.RootSessionViewModel
import io.github.stream29.kodex.cli.session.SessionRepositoryViewModel
import io.github.stream29.kodex.cli.agent.ComposerViewModel
import io.github.stream29.kodex.cli.settings.KodexGlobalSettingsStore
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.Reasoning
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path

/** State for one virtual New-session tab. */
public data class NewSessionViewState(
    public val settings: KodexNewSessionSettings,
    public val newLineKey: NewLineKey,
    public val codexHome: Path,
    /** A per-tab title to use when this virtual session is materialized. */
    public val threadName: String? = null,
    public val creating: Boolean = false,
    public val failureMessage: String? = null,
)

/**
 * Owns the settings copied from global new-session defaults and materializes root sessions from them.
 *
 * Each visible virtual New-session tab owns one instance and therefore one independent draft.
 *
 * This is not a Session ViewModel: it has no Agent runtime until [create] has committed a root
 * storage snapshot through [SessionRepositoryViewModel].
 */
public class NewSessionViewModel internal constructor(
    private val globalSettings: KodexGlobalSettingsStore,
    private val sessions: SessionRepositoryViewModel,
    private val workingDirectory: Path,
    private val scope: CoroutineScope,
) : AutoCloseable {
    /** Draft editor state used before a real root Agent runtime exists. */
    public val composer: ComposerViewModel = ComposerViewModel()

    private val creationMutex = Mutex()
    private val mutableState = MutableStateFlow(
        NewSessionViewState(
            settings = globalSettings.settings.value.newSession.copy(),
            newLineKey = globalSettings.settings.value.newLineKey,
            codexHome = globalSettings.settings.value.codexHome,
        ),
    )

    public val state: StateFlow<NewSessionViewState> = mutableState.asStateFlow()

    init {
        scope.launch {
            globalSettings.settings.collect { settings ->
                mutableState.update { current ->
                    current.copy(
                        newLineKey = settings.newLineKey,
                        codexHome = settings.codexHome,
                    )
                }
            }
        }
    }

    /** Updates settings for this virtual tab without modifying application-wide defaults. */
    public suspend fun updateSettings(
        transform: (KodexNewSessionSettings) -> KodexNewSessionSettings,
    ): KodexNewSessionSettings = creationMutex.withLock {
        val current = mutableState.value
        val updated = transform(current.settings)
        mutableState.value = current.copy(settings = updated)
        updated
    }

    /** Names this virtual tab; the title is applied when its root session is created. */
    public suspend fun renameThread(threadName: String): String {
        val normalized = threadName.trim()
        require(normalized.isNotEmpty()) { "A session name cannot be blank." }
        creationMutex.withLock {
            mutableState.update { current -> current.copy(threadName = normalized) }
        }
        return normalized
    }

    /** Persists the application-wide composer newline policy. */
    public suspend fun updateNewLineKey(newLineKey: NewLineKey): NewLineKey =
        globalSettings.update { settings -> settings.copy(newLineKey = newLineKey) }.newLineKey

    /** Creates one initialized root session from this virtual tab's settings draft. */
    public suspend fun create(): RootSessionViewModel = creationMutex.withLock {
        mutableState.update { current -> current.copy(creating = true, failureMessage = null) }
        try {
            val state = mutableState.value
            sessions.create { sessionIndex ->
                state.settings.toAgentSettings(sessionIndex, workingDirectory, state.threadName)
            }
        } catch (failure: Throwable) {
            mutableState.update { current ->
                current.copy(failureMessage = failure.message ?: failure.toString())
            }
            throw failure
        } finally {
            mutableState.update { current -> current.copy(creating = false) }
        }
    }

    override fun close() {
        scope.cancel()
    }
}

/** Creates one virtual New-session editor as a child of this scope. */
public fun CoroutineScope.NewSessionViewModel(
    globalSettings: KodexGlobalSettingsStore,
    sessions: SessionRepositoryViewModel,
    workingDirectory: Path,
): NewSessionViewModel =
    NewSessionViewModel(
        globalSettings = globalSettings,
        sessions = sessions,
        workingDirectory = workingDirectory,
        scope = supervisorChildScope(),
    )

private fun KodexNewSessionSettings.toAgentSettings(
    sessionIndex: Int,
    workingDirectory: Path,
    threadName: String?,
): KodexAgentSettings =
    KodexAgentSettings(
        model = model,
        cwd = workingDirectory,
        threadName = threadName ?: "Session $sessionIndex",
        collaborationMode = mode,
        reasoning = Reasoning(effort = reasoningEffort),
        serviceTier = serviceTier,
    )
