package io.github.stream29.kodex.app.settings

import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import io.github.stream29.kodex.app.settings.contract.SessionSettingsConfiguration
import io.github.stream29.kodex.app.settings.contract.SessionSettingsDataSource
import io.github.stream29.kodex.app.settings.contract.SessionSettingsDataState
import io.github.stream29.kodex.app.settings.contract.SessionSettingsEffect
import io.github.stream29.kodex.app.settings.contract.SessionSettingsState
import io.github.stream29.kodex.app.settings.contract.SessionSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.SessionWorkingDirectoryPicker
import io.github.stream29.kodex.openai.AgentMode
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.io.files.Path

internal class SessionSettingsViewModelImpl(
    private val source: SessionSettingsDataSource,
    models: StateFlow<List<ModelInfo>>,
    parentScope: CoroutineScope,
    private val createDirectoryPicker: (Path) -> DirectoryPickerViewModel?,
) : SessionSettingsViewModel {
    private val scope = parentScope.supervisorChildScope()
    private val updates = SettingsUpdateQueue(parentScope)
    private val effectChannel = Channel<SessionSettingsEffect>(Channel.BUFFERED)
    private var closed: Boolean = false
    private val mutableState = MutableStateFlow(source.state.value.toFrontendState(models.value))
    private val mutableDirectoryPicker =
        MutableStateFlow<SessionWorkingDirectoryPicker?>(null)

    override val state: StateFlow<SessionSettingsState> = mutableState.asStateFlow()
    override val directoryPicker: StateFlow<SessionWorkingDirectoryPicker?> =
        mutableDirectoryPicker.asStateFlow()
    override val effects: Flow<SessionSettingsEffect> = effectChannel.receiveAsFlow()

    init {
        scope.launch {
            combine(source.state, models) { target, modelCatalog ->
                target.toFrontendState(modelCatalog)
            }.collect { projected ->
                mutableState.value = projected
            }
        }
    }

    override fun updateModel(expectedRevision: Long, model: OpenAiModelId) {
        updateConfiguration(expectedRevision) { copy(model = model) }
    }

    override fun updateReasoningEffort(
        expectedRevision: Long,
        reasoningEffort: ReasoningEffort,
    ) {
        updateConfiguration(expectedRevision) { copy(reasoningEffort = reasoningEffort) }
    }

    override fun updateServiceTier(expectedRevision: Long, serviceTier: ServiceTier) {
        updateConfiguration(expectedRevision) { copy(serviceTier = serviceTier) }
    }

    override fun updateAgentMode(expectedRevision: Long, agentMode: AgentMode) {
        updateConfiguration(expectedRevision) { copy(agentMode = agentMode) }
    }

    override fun requestWorkingDirectory(expectedRevision: Long) {
        if (closed) return
        val available = currentWritable(expectedRevision) ?: return
        val child = createDirectoryPicker(
            available.snapshot.configuration.workingDirectory,
        ) ?: return
        val created = SessionWorkingDirectoryPicker(
            expectedRevision = expectedRevision,
            viewModel = child,
        )
        val replaced = mutableDirectoryPicker.value
        mutableDirectoryPicker.value = created
        replaced?.viewModel?.close()
    }

    override fun selectWorkingDirectory(
        expected: SessionWorkingDirectoryPicker,
        workingDirectory: Path,
    ): Boolean {
        if (!removeDirectoryPicker(expected)) return false
        updateConfiguration(expected.expectedRevision) {
            copy(workingDirectory = workingDirectory)
        }
        return true
    }

    override fun dismissWorkingDirectoryPicker(
        expected: SessionWorkingDirectoryPicker,
    ): Boolean = removeDirectoryPicker(expected)

    private fun removeDirectoryPicker(
        expected: SessionWorkingDirectoryPicker,
    ): Boolean {
        if (!mutableDirectoryPicker.compareAndSet(expected, null)) return false
        expected.viewModel.close()
        return true
    }

    override fun requestRename(expectedRevision: Long) {
        if (closed) return
        val available = currentExpected(expectedRevision) ?: return
        effectChannel.trySend(
            SessionSettingsEffect.RenameSession(
                expectedRevision = expectedRevision,
                initialName = available.snapshot.sessionName,
            ),
        )
    }

    override fun renameSession(expectedRevision: Long, sessionName: String) {
        if (closed) return
        val normalized = sessionName.trim()
        if (normalized.isEmpty() || currentExpected(expectedRevision) == null) return
        updates.submit {
            source.tryRenameSession(expectedRevision, normalized)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        mutableDirectoryPicker.value?.viewModel?.close()
        mutableDirectoryPicker.value = null
        effectChannel.close()
        scope.cancel()
        updates.close(source::close)
    }

    private fun updateConfiguration(
        expectedRevision: Long,
        transform: SessionSettingsConfiguration.() -> SessionSettingsConfiguration,
    ) {
        if (closed) return
        val available = currentWritable(expectedRevision) ?: return
        val configuration = available.snapshot.configuration.transform()
        updates.submit {
            source.tryUpdateConfiguration(expectedRevision, configuration)
        }
    }

    private fun currentWritable(expectedRevision: Long): SessionSettingsState.Available? {
        val available = currentExpected(expectedRevision) ?: return null
        if (!available.snapshot.editable) return null
        return available
    }

    private fun currentExpected(
        expectedRevision: Long,
    ): SessionSettingsState.Available? {
        val current = mutableState.value
        val available = current as? SessionSettingsState.Available
        if (available == null) return null
        if (available.snapshot.revision != expectedRevision) return null
        return available
    }
}

private fun SessionSettingsDataState.toFrontendState(
    models: List<ModelInfo>,
): SessionSettingsState =
    when (this) {
        SessionSettingsDataState.Unavailable -> SessionSettingsState.Unavailable
        is SessionSettingsDataState.Available -> SessionSettingsState.Available(
            snapshot = snapshot,
            modelOptions = (
                models.map(ModelInfo::slug) +
                    snapshot.configuration.model
                ).distinct(),
        )
    }

internal class UnavailableSessionSettingsDataSource : SessionSettingsDataSource {
    override val state: StateFlow<SessionSettingsDataState> =
        MutableStateFlow(SessionSettingsDataState.Unavailable)

    override suspend fun tryUpdateConfiguration(
        expectedRevision: Long,
        configuration: SessionSettingsConfiguration,
    ): Boolean = false

    override suspend fun tryRenameSession(
        expectedRevision: Long,
        sessionName: String,
    ): Boolean = false

    override fun close(): Unit = Unit
}
