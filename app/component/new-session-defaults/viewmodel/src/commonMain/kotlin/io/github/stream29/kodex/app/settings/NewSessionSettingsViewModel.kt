package io.github.stream29.kodex.app.settings

import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

public object DefaultNewSessionDefaultsViewModelFactory : NewSessionDefaultsViewModelFactory {
    override fun create(
        dependencies: NewSessionDefaultsDependencies,
        ownerScope: CoroutineScope,
    ): NewSessionSettingsViewModel = DefaultNewSessionSettingsViewModel(dependencies, ownerScope)
}

public fun createNewSessionDefaultsViewModel(
    dependencies: NewSessionDefaultsDependencies,
    ownerScope: CoroutineScope,
): NewSessionSettingsViewModel = DefaultNewSessionDefaultsViewModelFactory.create(dependencies, ownerScope)

private class DefaultNewSessionSettingsViewModel(
    private val dependencies: NewSessionDefaultsDependencies,
    ownerScope: CoroutineScope,
) : NewSessionSettingsViewModel {
    private val owner = SupervisorJob(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + owner)
    private var closed = false
    private val active get() = !closed && owner.isActive
    private val mutableState = MutableStateFlow(project(0, dependencies.defaults.value))
    override val state = mutableState.asStateFlow()
    override val operationFailure: StateFlow<Boolean> = dependencies.operationFailure

    init {
        scope.launch {
            combine(dependencies.defaults, dependencies.models) { _, _ -> Unit }.collect {
                if (active) refresh()
            }
        }
        owner.invokeOnCompletion { close() }
        if (!owner.isActive) close()
    }
    private fun project(revision: Long, value: KodexNewSessionSettings): NewSessionSettingsState =
        NewSessionSettingsState(revision, value, (dependencies.models.value.map { it.slug } + value.model).distinct())

    private fun refresh() {
        val previous = mutableState.value
        val value = dependencies.defaults.value
        mutableState.value = project(previous.revision + if (value == previous.settings) 0 else 1, value)
    }
    private fun edit(revision: Long, admission: (KodexNewSessionSettings) -> NewSessionDefaultsAdmission) {
        if (!active) return
        // Do not admit against an old projection if a source changed before its collector ran.
        refresh()
        val snapshot = mutableState.value
        if (snapshot.revision != revision) return
        try {
            admission(snapshot.settings)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            dependencies.reportFailure(failure)
        }
    }
    override fun updateModel(expectedRevision: Long, model: OpenAiModelId) {
        edit(expectedRevision) { dependencies.admitModel(it.model, model) }
    }
    override fun updateReasoningEffort(expectedRevision: Long, reasoningEffort: ReasoningEffort) {
        edit(expectedRevision) { dependencies.admitReasoningEffort(it.reasoningEffort, reasoningEffort) }
    }
    override fun updateServiceTier(expectedRevision: Long, serviceTier: ServiceTier) {
        edit(expectedRevision) { dependencies.admitServiceTier(it.serviceTier, serviceTier) }
    }
    override fun updateRequestUserInputMode(expectedRevision: Long, mode: RequestUserInputMode) {
        edit(expectedRevision) { dependencies.admitRequestUserInputMode(it.requestUserInputMode, mode) }
    }
    override fun dismissOperationFailure() { if (active) dependencies.dismissFailure() }
    override fun close() {
        if (closed) return
        closed = true
        mutableState.value = mutableState.value.copy(active = false)
        owner.cancel()
    }
}
