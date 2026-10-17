package io.github.stream29.kodex.app.accountusage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Create one short-lived observing child; the host retains navigation refresh responsibility. */
public fun createAccountUsageViewModel(
    dependencies: AccountUsageDependencies,
    ownerScope: CoroutineScope,
): AccountUsageViewModel = DefaultAccountUsageViewModel(dependencies, ownerScope)

private class DefaultAccountUsageViewModel(
    private val dependencies: AccountUsageDependencies,
    ownerScope: CoroutineScope,
) : AccountUsageViewModel {
    private val owner = Job(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + owner)
    private val mutable = MutableStateFlow(AccountUsageState(
        usage = dependencies.usage.value,
        operationFailure = dependencies.operationFailure.value,
    ))
    override val state: StateFlow<AccountUsageState> = mutable.asStateFlow()
    private val active: Boolean get() = owner.isActive && !mutable.value.closed

    init {
        scope.launch {
            dependencies.usage.collect { mutable.value = mutable.value.copy(usage = it) }
        }
        scope.launch {
            dependencies.operationFailure.collect { mutable.value = mutable.value.copy(operationFailure = it) }
        }
        owner.invokeOnCompletion { close() }
        if (!owner.isActive) close()
    }

    override fun refresh() {
        if (!active) return
        scope.launch {
            try {
                dependencies.refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                dependencies.reportFailure(failure)
            }
        }
    }

    override fun requestReset() {
        if (!active) return
        try {
            dependencies.requestReset()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            dependencies.reportFailure(failure)
        }
    }

    override fun dismissFailure() { if (active) dependencies.dismissFailure() }

    override fun close() {
        if (mutable.value.closed) return
        mutable.value = mutable.value.copy(closed = true)
        owner.cancel()
    }
}
