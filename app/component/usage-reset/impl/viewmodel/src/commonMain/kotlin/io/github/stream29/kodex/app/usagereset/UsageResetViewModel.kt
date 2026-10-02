package io.github.stream29.kodex.app.usagereset

import io.github.stream29.kodex.app.settings.contract.UsageResetOption
import io.github.stream29.kodex.app.settings.contract.UsageResetRequest
import io.github.stream29.kodex.app.settings.contract.UsageResetState
import io.github.stream29.kodex.app.settings.contract.snapshotOrNull
import io.github.stream29.kodex.app.usagereset.contract.UsageResetDependencies
import io.github.stream29.kodex.app.usagereset.contract.UsageResetViewModel
import io.github.stream29.kodex.app.usagereset.contract.UsageResetViewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Dependency-only implementation of the typed spec factory. */
public object DefaultUsageResetViewModelFactory : UsageResetViewModelFactory {
    override fun create(
        dependencies: UsageResetDependencies,
        ownerScope: CoroutineScope,
    ): UsageResetViewModel = DefaultUsageResetViewModel(dependencies, ownerScope)
}

/**
 * Creates an initially hidden child on [ownerScope]'s dispatcher with a linked
 * local Job. Construction performs no I/O. Host closes the child explicitly;
 * cancellation propagates from owner to child, never from child to shared owner.
 */
public fun createUsageResetViewModel(
    dependencies: UsageResetDependencies,
    ownerScope: CoroutineScope,
): UsageResetViewModel = DefaultUsageResetViewModelFactory.create(dependencies, ownerScope)

private class DefaultUsageResetViewModel(
    private val dependencies: UsageResetDependencies,
    ownerScope: CoroutineScope,
) : UsageResetViewModel {
    private val owner = Job(ownerScope.coroutineContext[Job])
    private val local = CoroutineScope(ownerScope.coroutineContext + owner)
    private val mutable = MutableStateFlow<UsageResetState>(UsageResetState.Hidden)
    override val state: StateFlow<UsageResetState> = mutable.asStateFlow()
    private var operationActive = false
    private var choicesRefreshing = false
    private var generation = 0L

    init {
        owner.invokeOnCompletion { mutable.value = UsageResetState.Hidden }
    }

    override fun show() {
        if (!owner.isActive || operationActive) return
        generation++
        val credits = dependencies.usage.value.snapshotOrNull()?.resetCredits
        val options = credits?.credits.orEmpty().filter { it.id.isNotBlank() }.map { credit ->
            UsageResetOption(
                credit.id,
                credit.title?.takeIf { it.isNotBlank() } ?: "Full reset",
                credit.description?.takeIf { it.isNotBlank() } ?: "Reset current usage limits.",
                credit.expiresAt,
            )
        }
        mutable.value = if (options.isEmpty()) UsageResetState.PreparationFailed
        else UsageResetState.Choosing(
            UsageResetRequest(
                (credits?.availableCount ?: options.size.toLong()).coerceAtLeast(options.size.toLong()),
                options,
            ),
        )
    }

    override fun select(creditId: String) {
        if (!owner.isActive || operationActive || creditId.isBlank()) return
        val choosing = mutable.value as? UsageResetState.Choosing ?: return
        val option = choosing.request.options.singleOrNull { it.creditId == creditId } ?: return
        mutable.value = UsageResetState.Confirming(option)
    }

    override fun confirm(expected: UsageResetState.Confirming) {
        if (!owner.isActive || operationActive || mutable.value !== expected) return
        val creditId = expected.option.creditId?.takeIf { it.isNotBlank() } ?: return
        operationActive = true
        mutable.value = UsageResetState.Consuming(expected.option)
        local.launch {
            try {
                val result = try {
                    val outcome = dependencies.consume(creditId)
                    currentCoroutineContext().ensureActive()
                    if (!owner.isActive) return@launch
                    UsageResetState.Completed(outcome, selectedCredit = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    currentCoroutineContext().ensureActive()
                    if (!owner.isActive) return@launch
                    UsageResetState.ConsumeFailed(expected.option)
                }
                mutable.value = result
                if (result is UsageResetState.Completed) return@launch
                currentCoroutineContext().ensureActive()
                if (!owner.isActive) return@launch
                try {
                    dependencies.refreshUsage()
                    currentCoroutineContext().ensureActive()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // Refresh does not establish whether an unknown command committed.
                    // Keep the unknown failure; normal results bypass this refresh.
                }
            } finally {
                operationActive = false
            }
        }
    }

    override fun back() {
        if (mutable.value is UsageResetState.Confirming) show()
    }

    override fun retry() {
        if (mutable.value is UsageResetState.ConsumeFailed) show()
    }

    override fun refreshChoices() {
        if (!owner.isActive || operationActive || choicesRefreshing ||
            mutable.value !== UsageResetState.PreparationFailed
        ) return
        choicesRefreshing = true
        val expectedGeneration = generation
        local.launch {
            try {
                dependencies.refreshUsage()
                currentCoroutineContext().ensureActive()
                if (owner.isActive && generation == expectedGeneration) show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // No new snapshot is claimed after a failed refresh.
            } finally {
                choicesRefreshing = false
            }
        }
    }

    override fun dismiss() {
        if (!owner.isActive || operationActive) return
        generation++
        mutable.value = UsageResetState.Hidden
    }

    override fun close() {
        generation++
        owner.cancel()
        mutable.value = UsageResetState.Hidden
    }
}
