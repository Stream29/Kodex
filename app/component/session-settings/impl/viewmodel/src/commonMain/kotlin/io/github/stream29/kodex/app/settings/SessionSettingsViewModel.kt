package io.github.stream29.kodex.app.settings

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import io.github.stream29.kodex.app.sessionrename.createSessionRenameViewModel
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameDependencies
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.app.workingdirectory.createWorkingDirectoryViewModel
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryDependencies
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.utils.logging.global
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path

public object DefaultSessionSettingsViewModelFactory : SessionSettingsViewModelFactory {
    override fun create(
        dependencies: SessionSettingsDependencies,
        ownerScope: CoroutineScope,
    ): SessionSettingsViewModel = SessionSettingsViewModelImpl(dependencies, ownerScope)
}

public fun createSessionSettingsViewModel(
    dependencies: SessionSettingsDependencies,
    ownerScope: CoroutineScope,
): SessionSettingsViewModel = DefaultSessionSettingsViewModelFactory.create(dependencies, ownerScope)

/** Compatibility creation seam; the same dependency-only implementation and cancel-on-close policy. */
public fun createSessionSettingsViewModel(
    source: SessionSettingsDataSource,
    models: StateFlow<List<ModelInfo>>,
    ownerScope: CoroutineScope,
    createDirectoryPicker: (Path) -> DirectoryPickerViewModel? = { null },
    reportUnhandledError: ((Throwable, Path) -> Unit)? = null,
): SessionSettingsViewModel = createSessionSettingsViewModel(
    SessionSettingsDependencies(source, models, createDirectoryPicker, reportUnhandledError), ownerScope,
)

private class SessionSettingsViewModelImpl(
    private val dependencies: SessionSettingsDependencies,
    ownerScope: CoroutineScope,
) : SessionSettingsViewModel {
    private val source = dependencies.source
    private val owner = SupervisorJob(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + owner)
    private var closed = false
    private val active get() = !closed && owner.isActive
    private class Command(val cwd: Path, val execute: suspend () -> Unit)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val effectChannel = Channel<SessionSettingsEffect>(Channel.BUFFERED)
    private val mutableState = MutableStateFlow(project())
    private val mutableDirectoryPicker = MutableStateFlow<SessionWorkingDirectoryPicker?>(null)
    private val mutableRename = MutableStateFlow<SessionSettingsRename?>(null)
    override val state = mutableState.asStateFlow()
    override val directoryPicker = mutableDirectoryPicker.asStateFlow()
    override val rename = mutableRename.asStateFlow()
    override val effects = effectChannel.receiveAsFlow()

    init {
        scope.launch {
            combine(source.state, dependencies.models) { _, _ -> Unit }.collect {
                if (active) mutableState.value = project()
            }
        }
        scope.launch {
            try {
                for (command in commands) {
                    try {
                        command.execute()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        dependencies.reportUnhandledError?.invoke(failure, command.cwd)
                            ?: logger.error(failure) { "Failed to persist a Settings update." }
                    }
                }
            } finally {
                // A cancelled retry must not leave a dead queue admitting later commands.
                close()
            }
        }
        owner.invokeOnCompletion { close() }
        if (!owner.isActive) close()
    }

    private fun project(): SessionSettingsState = when (val data = source.state.value) {
        SessionSettingsDataState.Unavailable -> SessionSettingsState.Unavailable
        is SessionSettingsDataState.Available -> SessionSettingsState.Available(
            data.snapshot, (dependencies.models.value.map { it.slug } + data.snapshot.configuration.model).distinct(),
        )
    }

    private fun currentExpected(revision: Long): SessionSettingsSnapshot? {
        if (!active) return null
        val snapshot = (source.state.value as? SessionSettingsDataState.Available)?.snapshot ?: return null
        return snapshot.takeIf { it.revision == revision }
    }
    private fun currentWritable(revision: Long): SessionSettingsSnapshot? =
        currentExpected(revision)?.takeIf { it.editable }

    override fun updateModel(expectedRevision: Long, model: OpenAiModelId) {
        updateConfiguration(expectedRevision) { copy(model = model) }
    }
    override fun updateReasoningEffort(expectedRevision: Long, reasoningEffort: ReasoningEffort) {
        updateConfiguration(expectedRevision) { copy(reasoningEffort = reasoningEffort) }
    }
    override fun updateServiceTier(expectedRevision: Long, serviceTier: ServiceTier) {
        updateConfiguration(expectedRevision) { copy(serviceTier = serviceTier) }
    }
    override fun updateRequestUserInputMode(expectedRevision: Long, mode: RequestUserInputMode) {
        updateConfiguration(expectedRevision) { copy(requestUserInputMode = mode) }
    }
    private fun updateConfiguration(
        revision: Long,
        transform: SessionSettingsConfiguration.() -> SessionSettingsConfiguration,
    ) {
        val snapshot = currentWritable(revision) ?: return
        val configuration = snapshot.configuration.transform()
        commands.trySend(Command(snapshot.configuration.workingDirectory) {
            source.tryUpdateConfiguration(revision, configuration)
        })
    }

    override fun requestWorkingDirectory(expectedRevision: Long) {
        val snapshot = currentWritable(expectedRevision) ?: return
        val browser = dependencies.createDirectoryPicker(snapshot.configuration.workingDirectory) ?: return
        lateinit var handle: SessionWorkingDirectoryPicker
        handle = SessionWorkingDirectoryPicker(
            expectedRevision,
            createWorkingDirectoryViewModel(browser, WorkingDirectoryDependencies { path ->
                selectWorkingDirectory(handle, path)
            }),
        )
        hidePage()
        mutableDirectoryPicker.value = handle
    }
    override fun selectWorkingDirectory(expected: SessionWorkingDirectoryPicker, workingDirectory: Path): Boolean {
        if (!dismissWorkingDirectoryPicker(expected)) return false
        updateConfiguration(expected.expectedRevision) { copy(workingDirectory = workingDirectory) }
        return true
    }
    override fun dismissWorkingDirectoryPicker(expected: SessionWorkingDirectoryPicker): Boolean {
        if (!mutableDirectoryPicker.compareAndSet(expected, null)) return false
        expected.selection.close()
        return true
    }

    override fun requestRename(expectedRevision: Long) {
        val snapshot = currentExpected(expectedRevision) ?: return
        lateinit var handle: SessionSettingsRename
        handle = SessionSettingsRename(
            expectedRevision,
            createSessionRenameViewModel(snapshot.sessionName, SessionRenameDependencies { name ->
                // A delayed invocation from a disposed/replaced child cannot gain target authority.
                if (mutableRename.value === handle) renameSession(expectedRevision, name)
            }),
        )
        hidePage()
        mutableRename.value = handle
        effectChannel.trySend(SessionSettingsEffect.RenameSession(expectedRevision, snapshot.sessionName))
    }
    override fun dismissRename(expected: SessionSettingsRename): Boolean {
        if (!mutableRename.compareAndSet(expected, null)) return false
        expected.viewModel.close()
        return true
    }
    override fun renameSession(expectedRevision: Long, sessionName: String) {
        val normalized = sessionName.trim()
        val snapshot = currentExpected(expectedRevision) ?: return
        if (normalized.isEmpty()) return
        commands.trySend(Command(snapshot.configuration.workingDirectory) {
            source.tryRenameSession(expectedRevision, normalized)
        })
    }

    override fun hidePage() {
        mutableDirectoryPicker.value?.let(::dismissWorkingDirectoryPicker)
        mutableRename.value?.let(::dismissRename)
    }
    override fun close() {
        if (closed) return
        closed = true
        hidePage()
        effectChannel.close()
        commands.cancel()
        owner.cancel()
        source.close()
        mutableState.value = SessionSettingsState.Unavailable
    }
}

private val logger by lazy { KotlinLogging.logger {}.global() }
