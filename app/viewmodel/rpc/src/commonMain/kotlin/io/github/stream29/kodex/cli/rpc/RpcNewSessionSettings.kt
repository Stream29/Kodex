package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.app.settings.contract.NewSessionSettingsState
import io.github.stream29.kodex.app.settings.contract.NewSessionSettingsViewModel
import io.github.stream29.kodex.app.settings.SettingsUpdateQueue
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** A disposable defaults editor whose accepted writes drain in the app scope. */
public class RpcNewSessionSettings(
    private val global: RpcGlobalSettings,
    applicationScope: CoroutineScope,
) : NewSessionSettingsViewModel {
    private val owner = Job(applicationScope.coroutineContext[Job])
    private val local = CoroutineScope(applicationScope.coroutineContext + owner)
    private val updates = SettingsUpdateQueue(applicationScope)
    private val mutable = MutableStateFlow(project(0, global.settings.value.newSession))
    override val state: StateFlow<NewSessionSettingsState> = mutable.asStateFlow()

    init {
        local.launch {
            combine(global.settings, global.models) { settings, _ -> settings.newSession }.collect { value ->
                val previous = mutable.value
                mutable.value = project(previous.revision + if (value == previous.settings) 0 else 1, value)
            }
        }
    }

    private fun project(revision: Long, value: KodexNewSessionSettings): NewSessionSettingsState =
        NewSessionSettingsState(revision, value, (global.models.value.map { it.slug } + value.model).distinct())

    private fun <F> edit(
        revision: Long,
        select: (KodexNewSessionSettings) -> F,
        replace: (KodexNewSessionSettings) -> KodexNewSessionSettings,
    ) {
        if (!owner.isActive || state.value.revision != revision) return
        val initial = select(state.value.settings)
        updates.submit {
            try {
                global.settings.editField(initial, { select(it.newSession) },
                    { it.copy(newSession = replace(it.newSession)) },
                    global::ensureActive,
                )
                global.dismissOperationFailure()
            } catch (error: CancellationException) { throw error }
            catch (error: Throwable) { global.reportOperationFailure(error) }
        }
    }

    override fun updateModel(expectedRevision: Long, model: OpenAiModelId): Unit =
        edit(expectedRevision, { it.model }, { it.copy(model = model) })
    override fun updateReasoningEffort(expectedRevision: Long, reasoningEffort: ReasoningEffort): Unit =
        edit(expectedRevision, { it.reasoningEffort }, { it.copy(reasoningEffort = reasoningEffort) })
    override fun updateServiceTier(expectedRevision: Long, serviceTier: ServiceTier): Unit =
        edit(expectedRevision, { it.serviceTier }, { it.copy(serviceTier = serviceTier) })
    override fun updateRequestUserInputMode(expectedRevision: Long, mode: RequestUserInputMode): Unit =
        edit(expectedRevision, { it.requestUserInputMode }, { it.copy(requestUserInputMode = mode) })
    override fun close() { owner.cancel(); updates.close() }
}
