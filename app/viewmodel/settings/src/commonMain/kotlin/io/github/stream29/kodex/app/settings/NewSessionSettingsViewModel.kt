package io.github.stream29.kodex.app.settings

import io.github.stream29.kodex.app.settings.contract.NewSessionSettingsState
import io.github.stream29.kodex.app.settings.contract.NewSessionSettingsViewModel
import io.github.stream29.kodex.cli.settings.KodexGlobalSettingsStore
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal class NewSessionSettingsViewModelImpl(
    private val globalSettings: KodexGlobalSettingsStore,
    models: StateFlow<List<ModelInfo>>,
    parentScope: CoroutineScope,
    reportUnhandledError: ((Throwable) -> Unit)? = null,
) : NewSessionSettingsViewModel {
    private val scope = parentScope.supervisorChildScope()
    private val updates = SettingsUpdateQueue(parentScope, defaultReportError = reportUnhandledError)
    private var closed: Boolean = false
    private var latestSettings: KodexNewSessionSettings = globalSettings.settings.value.newSession
    private var revision: Long = 0
    private val mutableState = MutableStateFlow(
        latestSettings.toNewSessionSettingsState(revision, models.value),
    )

    override val state: StateFlow<NewSessionSettingsState> = mutableState.asStateFlow()

    init {
        scope.launch {
            combine(globalSettings.settings, models) { settings, modelCatalog ->
                settings.newSession to modelCatalog
            }.collect { (settings, modelCatalog) ->
                if (settings != latestSettings) {
                    check(revision < Long.MAX_VALUE) {
                        "New Session Settings revisions are exhausted."
                    }
                    latestSettings = settings
                    revision += 1
                }
                mutableState.value = settings.toNewSessionSettingsState(revision, modelCatalog)
            }
        }
    }

    override fun updateModel(expectedRevision: Long, model: OpenAiModelId) {
        update(expectedRevision) { copy(model = model) }
    }

    override fun updateReasoningEffort(
        expectedRevision: Long,
        reasoningEffort: ReasoningEffort,
    ) {
        update(expectedRevision) { copy(reasoningEffort = reasoningEffort) }
    }

    override fun updateServiceTier(expectedRevision: Long, serviceTier: ServiceTier) {
        update(expectedRevision) { copy(serviceTier = serviceTier) }
    }

    override fun updateRequestUserInputMode(
        expectedRevision: Long,
        mode: RequestUserInputMode,
    ) {
        update(expectedRevision) { copy(requestUserInputMode = mode) }
    }

    override fun close() {
        if (closed) return
        closed = true
        updates.close()
        scope.cancel()
    }

    private fun update(
        expectedRevision: Long,
        transform: KodexNewSessionSettings.() -> KodexNewSessionSettings,
    ) {
        if (closed) return
        val expected = mutableState.value
        if (expected.revision != expectedRevision) return
        updates.submit {
            globalSettings.update { current ->
                if (current.newSession != expected.settings) {
                    current
                } else {
                    current.copy(newSession = current.newSession.transform())
                }
            }
        }
    }
}

private fun KodexNewSessionSettings.toNewSessionSettingsState(
    revision: Long,
    models: List<ModelInfo>,
): NewSessionSettingsState =
    NewSessionSettingsState(
        revision = revision,
        settings = this,
        modelOptions = (models.map(ModelInfo::slug) + model).distinct(),
    )
