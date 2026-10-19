package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.app.settings.contract.SessionSettingsConfiguration
import io.github.stream29.kodex.app.settings.contract.SessionSettingsDataSource
import io.github.stream29.kodex.app.settings.contract.SessionSettingsDataState
import io.github.stream29.kodex.app.settings.contract.SessionSettingsSnapshot
import io.github.stream29.kodex.openai.KodexAgentSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A popup targets this exact binding; reactivation cannot silently retarget an old editor. */
public class RpcSessionSettingsSource(
    private val view: RpcSessionView,
    scope: CoroutineScope,
) : SessionSettingsDataSource {
    private val binding = view.current()
    private val owner = Job(scope.coroutineContext[Job])
    private val local = CoroutineScope(scope.coroutineContext + owner)
    private val lock = Mutex()
    private val mutable = MutableStateFlow<SessionSettingsDataState>(available(0))
    override val state: StateFlow<SessionSettingsDataState> = mutable.asStateFlow()

    init {
        local.launch {
            try {
                combine(binding.settings, view.binding) { _, current -> current }.collect { current ->
                    if (current !== binding) {
                        mutable.value = SessionSettingsDataState.Unavailable
                        owner.cancel()
                    } else {
                        val previous = mutable.value as? SessionSettingsDataState.Available ?: return@collect
                        val candidate = available(previous.snapshot.revision)
                        if (candidate != previous) {
                            mutable.value = candidate.copy(snapshot = candidate.snapshot.copy(
                                revision = previous.snapshot.revision + 1,
                            ))
                        }
                    }
                }
            } finally {
                mutable.value = SessionSettingsDataState.Unavailable
            }
        }
    }

    private fun available(revision: Long): SessionSettingsDataState.Available {
        val value = binding.settings.value
        return SessionSettingsDataState.Available(SessionSettingsSnapshot(
            revision, value.threadName, value.configuration(), editable = true,
        ))
    }

    private fun checkOwner() {
        owner.ensureActive()
        binding.ensureActive()
        check(view.binding.value === binding) { "The settings target expired." }
    }

    override suspend fun tryUpdateConfiguration(
        expectedRevision: Long,
        configuration: SessionSettingsConfiguration,
    ): Boolean = lock.withLock {
        val expected = (state.value as? SessionSettingsDataState.Available)?.snapshot ?: return false
        if (expected.revision != expectedRevision) return false
        val original = expected.configuration
        fun select(value: SessionSettingsConfiguration): List<Any?> = buildList {
            if (original.model != configuration.model) add(value.model)
            if (original.workingDirectory != configuration.workingDirectory) add(value.workingDirectory)
            if (original.reasoningEffort != configuration.reasoningEffort) add(value.reasoningEffort)
            if (original.serviceTier != configuration.serviceTier) add(value.serviceTier)
            if (original.requestUserInputMode != configuration.requestUserInputMode) add(value.requestUserInputMode)
        }
        binding.settings.editField(
            select(original), { select(it.configuration()) },
            { current -> current.withConfiguration(current.configuration().copy(
                model = if (original.model != configuration.model) configuration.model else current.model,
                workingDirectory = if (original.workingDirectory != configuration.workingDirectory)
                    configuration.workingDirectory else current.cwd,
                reasoningEffort = if (original.reasoningEffort != configuration.reasoningEffort)
                    configuration.reasoningEffort else current.reasoning.effort,
                serviceTier = if (original.serviceTier != configuration.serviceTier)
                    configuration.serviceTier else current.serviceTier,
                requestUserInputMode = if (original.requestUserInputMode != configuration.requestUserInputMode)
                    configuration.requestUserInputMode else current.requestUserInputMode,
            )) }, ::checkOwner,
        )
    }

    override suspend fun tryRenameSession(expectedRevision: Long, sessionName: String): Boolean = lock.withLock {
        require(sessionName.isNotBlank())
        val expected = (state.value as? SessionSettingsDataState.Available)?.snapshot ?: return false
        if (expected.revision != expectedRevision) return false
        binding.settings.editField(
            expected.sessionName, { it.threadName }, { it.copy(threadName = sessionName) }, ::checkOwner,
        )
    }

    override fun close() {
        mutable.value = SessionSettingsDataState.Unavailable
        owner.cancel()
    }
}

internal fun KodexAgentSettings.configuration(): SessionSettingsConfiguration = SessionSettingsConfiguration(
    model, cwd, reasoning.effort, serviceTier, requestUserInputMode,
)

internal fun KodexAgentSettings.withConfiguration(value: SessionSettingsConfiguration): KodexAgentSettings = copy(
    model = value.model, cwd = value.workingDirectory,
    reasoning = reasoning.copy(effort = value.reasoningEffort),
    serviceTier = value.serviceTier, requestUserInputMode = value.requestUserInputMode,
)
