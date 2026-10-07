package io.github.stream29.kodex.app.runtimeconfiguration

import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.RequestUserInputMode
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.openai.availableServiceTiers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

public object DefaultRuntimeConfigurationViewModelFactory : RuntimeConfigurationViewModelFactory {
    override fun create(
        dependencies: RuntimeConfigurationDependencies,
        ownerScope: CoroutineScope,
    ): RuntimeConfigurationViewModel = DefaultRuntimeConfigurationViewModel(dependencies, ownerScope)
}

public fun createRuntimeConfigurationViewModel(
    dependencies: RuntimeConfigurationDependencies,
    ownerScope: CoroutineScope,
): RuntimeConfigurationViewModel =
    DefaultRuntimeConfigurationViewModelFactory.create(dependencies, ownerScope)

private class DefaultRuntimeConfigurationViewModel(
    private val dependencies: RuntimeConfigurationDependencies,
    ownerScope: CoroutineScope,
) : RuntimeConfigurationViewModel {
    private val owner = Job(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + owner)
    private val mutable = MutableStateFlow(project(dependencies.configuration.value, dependencies.models.value))
    override val state: StateFlow<RuntimeConfigurationState> = mutable.asStateFlow()
    private val waits = mutableSetOf<Job>()
    private val active: Boolean get() = owner.isActive && !mutable.value.closed

    init {
        scope.launch {
            combine(dependencies.configuration, dependencies.models, ::project)
                .collect { if (active) mutable.value = it }
        }
        owner.invokeOnCompletion { close() }
        if (!owner.isActive) close()
    }

    override suspend fun updateModelConfiguration(
        model: OpenAiModelId,
        effort: ReasoningEffort,
        tier: ServiceTier,
    ) {
        submit { dependencies.updateModelConfiguration(model, effort, tier) }
    }

    override suspend fun updateRequestUserInputMode(mode: RequestUserInputMode) {
        submit { dependencies.updateRequestUserInputMode(mode) }
    }

    // This scope is a child of the CALLER, not the observation owner. Tracking its job lets close
    // cancel only this local wait, without cancelling the caller's unrelated work or borrowed owner.
    private suspend fun submit(action: suspend () -> Unit) {
        if (!active) return
        coroutineScope {
            currentCoroutineContext().ensureActive()
            val wait = currentCoroutineContext().job
            waits += wait
            try {
                if (active) action()
            } finally {
                waits -= wait
            }
        }
    }

    override fun close() {
        if (mutable.value.closed) return
        mutable.value = mutable.value.copy(closed = true)
        waits.toList().forEach { it.cancel() }
        owner.cancel()
    }
}

private fun project(
    configuration: RuntimeConfiguration,
    models: List<ModelInfo>,
): RuntimeConfigurationState = RuntimeConfigurationState(
    configuration = configuration,
    modelOptions = (models.map(ModelInfo::slug) + configuration.model).distinct().map { model ->
        val info = models.firstOrNull { it.slug == model }
        RuntimeConfigurationModelOption(
            model = model,
            efforts = info?.supportedReasoningLevels?.map { it.effort }.orEmpty()
                .ifEmpty { listOf(configuration.reasoning) },
            tiers = info?.availableServiceTiers().orEmpty().ifEmpty { listOf(ServiceTier.Default) },
        )
    },
)
