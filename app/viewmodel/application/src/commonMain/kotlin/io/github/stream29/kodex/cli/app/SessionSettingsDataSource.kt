package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.session.contract.SessionViewModel
import io.github.stream29.kodex.app.settings.contract.SessionSettingsConfiguration
import io.github.stream29.kodex.app.settings.contract.SessionSettingsDataSource
import io.github.stream29.kodex.app.settings.contract.SessionSettingsDataState
import io.github.stream29.kodex.app.settings.contract.SessionSettingsSnapshot
import io.github.stream29.kodex.app.settings.contract.SessionSettingsTargetKind
import io.github.stream29.kodex.openai.KodexAgentSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Adapts one exact Session contract to the revision-bound Settings child port.
 *
 * The target is captured once; no command resolves the application's current
 * selection again.
 */
internal class ContractSessionSettingsDataSource(
    private val target: SessionViewModel,
    private val scope: CoroutineScope,
) : SessionSettingsDataSource {
    private val commandMutex = Mutex()
    private val mutableState = MutableStateFlow<SessionSettingsDataState>(
        SessionSettingsDataState.Available(project(revision = 0)),
    )
    private var revision = 0L
    private var closed = false

    override val state: StateFlow<SessionSettingsDataState> = mutableState.asStateFlow()

    init {
        scope.launch {
            combine(target.name, target.settings) { _, _ -> Unit }.collect {
                publish()
            }
        }
        if (target is PersistedSessionViewModel) {
            scope.launch {
                target.rootAgent.execution.collect { publish() }
            }
        }
    }

    override suspend fun tryUpdateConfiguration(
        expectedRevision: Long,
        configuration: SessionSettingsConfiguration,
    ): Boolean = commandMutex.withLock {
        val expected = expected(expectedRevision) ?: return@withLock false
        if (!expected.editable || !target.isWritable) return@withLock false
        if (target.settings.value.toConfiguration() != expected.configuration) {
            return@withLock false
        }
        val current = target.settings.value
        if (configuration.model != current.model) target.updateModel(configuration.model)
        if (configuration.workingDirectory != current.cwd) {
            target.updateWorkingDirectory(configuration.workingDirectory)
        }
        if (configuration.reasoningEffort != current.reasoning.effort) {
            target.updateReasoningEffort(configuration.reasoningEffort)
        }
        if (configuration.serviceTier != current.serviceTier) {
            target.updateServiceTier(configuration.serviceTier)
        }
        if (configuration.mode != current.collaborationMode) {
            target.updateMode(configuration.mode)
        }
        publish(configurationOverride = configuration)
        true
    }

    override suspend fun tryRenameSession(
        expectedRevision: Long,
        sessionName: String,
    ): Boolean = commandMutex.withLock {
        val expected = expected(expectedRevision) ?: return@withLock false
        val normalized = sessionName.trim()
        if (normalized.isEmpty() || target.name.value != expected.sessionName) {
            return@withLock false
        }
        target.rename(normalized)
        publish(nameOverride = normalized)
        true
    }

    override fun close() {
        if (closed) return
        closed = true
        mutableState.value = SessionSettingsDataState.Unavailable
        scope.cancel()
    }

    private fun publish(
        nameOverride: String? = null,
        configurationOverride: SessionSettingsConfiguration? = null,
    ) {
        if (closed) return
        val projected = project(
            revision = revision,
            nameOverride = nameOverride,
            configurationOverride = configurationOverride,
        )
        val current = (mutableState.value as? SessionSettingsDataState.Available)?.snapshot
        if (current?.copy(revision = 0) == projected.copy(revision = 0)) return
        check(revision < Long.MAX_VALUE) { "Session Settings revisions are exhausted." }
        revision += 1
        mutableState.value = SessionSettingsDataState.Available(
            projected.copy(revision = revision),
        )
    }

    private fun project(
        revision: Long,
        nameOverride: String? = null,
        configurationOverride: SessionSettingsConfiguration? = null,
    ): SessionSettingsSnapshot = SessionSettingsSnapshot(
        revision = revision,
        targetKind = when (target) {
            is PersistedSessionViewModel -> SessionSettingsTargetKind.MaterializedSession
            is NewSessionViewModel -> SessionSettingsTargetKind.NewSessionDraft
        },
        sessionName = nameOverride ?: target.name.value,
        configuration = configurationOverride ?: target.settings.value.toConfiguration(),
        editable = target.isWritable,
    )

    private fun expected(expectedRevision: Long): SessionSettingsSnapshot? {
        if (closed) return null
        val current = mutableState.value as? SessionSettingsDataState.Available ?: return null
        return current.snapshot.takeIf { snapshot -> snapshot.revision == expectedRevision }
    }

    private val SessionViewModel.isWritable: Boolean
        get() = when (this) {
            is NewSessionViewModel -> true
            is PersistedSessionViewModel -> !rootAgent.execution.value.running
        }
}

private fun KodexAgentSettings.toConfiguration(): SessionSettingsConfiguration =
    SessionSettingsConfiguration(
        model = model,
        workingDirectory = cwd,
        reasoningEffort = reasoning.effort,
        serviceTier = serviceTier,
        mode = collaborationMode,
    )
