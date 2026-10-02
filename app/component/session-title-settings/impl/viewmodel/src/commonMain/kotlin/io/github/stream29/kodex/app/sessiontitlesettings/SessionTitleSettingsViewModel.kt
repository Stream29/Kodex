package io.github.stream29.kodex.app.sessiontitlesettings

import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

public object DefaultSessionTitleSettingsViewModelFactory : SessionTitleSettingsViewModelFactory {
    override fun create(
        dependencies: SessionTitleSettingsDependencies, ownerScope: CoroutineScope,
    ): SessionTitleSettingsViewModel = DefaultSessionTitleSettingsViewModel(dependencies, ownerScope)
}

public fun createSessionTitleSettingsViewModel(
    dependencies: SessionTitleSettingsDependencies, ownerScope: CoroutineScope,
): SessionTitleSettingsViewModel = DefaultSessionTitleSettingsViewModelFactory.create(dependencies, ownerScope)

private class DefaultSessionTitleSettingsViewModel(
    private val dependencies: SessionTitleSettingsDependencies,
    ownerScope: CoroutineScope,
) : SessionTitleSettingsViewModel {
    private val owner = Job(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + owner)
    private val mutable = MutableStateFlow(project())
    override val state: StateFlow<SessionTitleSettingsState> = mutable.asStateFlow()
    private val active: Boolean get() = owner.isActive && !mutable.value.closed

    init {
        scope.launch {
            combine(dependencies.settings, dependencies.models, dependencies.operationFailure) { _, _, _ -> Unit }
                .collect { if (active) mutable.value = project() }
        }
        owner.invokeOnCompletion { close() }
        if (!owner.isActive) close()
    }

    private fun project(): SessionTitleSettingsState {
        val settings = dependencies.settings.value
        val effective = settings.model ?: dependencies.defaultModel
        return SessionTitleSettingsState(
            enabled = settings.enabled,
            configuredModel = settings.model,
            effectiveModel = effective,
            modelOptions = (dependencies.models.value.map { it.slug } + effective).distinct(),
            reasoningEffort = settings.reasoningEffort,
            operationFailure = dependencies.operationFailure.value,
        )
    }

    override fun setEnabled(enabled: Boolean) {
        if (!active) return
        val expected = dependencies.settings.value.enabled
        admit { dependencies.setEnabled(expected, enabled) }
    }
    override fun setModel(model: OpenAiModelId?) {
        if (!active) return
        val expected = dependencies.settings.value.model
        admit { dependencies.setModel(expected, model) }
    }
    override fun setReasoningEffort(reasoningEffort: ReasoningEffort) {
        if (!active) return
        val expected = dependencies.settings.value.reasoningEffort
        admit { dependencies.setReasoningEffort(expected, reasoningEffort) }
    }

    private fun admit(write: () -> SessionTitleWriteAdmission) {
        try {
            if (write() == SessionTitleWriteAdmission.Rejected) {
                dependencies.reportFailure(IllegalStateException("Session title admission rejected."))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            dependencies.reportFailure(failure)
        }
    }

    override fun hidePage() = Unit
    override fun dismissFailure() { if (active) dependencies.dismissFailure() }
    override fun close() {
        if (mutable.value.closed) return
        mutable.value = mutable.value.copy(closed = true)
        owner.cancel()
    }
}
