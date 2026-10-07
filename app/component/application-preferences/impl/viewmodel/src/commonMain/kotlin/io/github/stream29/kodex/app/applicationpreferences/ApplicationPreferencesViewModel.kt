package io.github.stream29.kodex.app.applicationpreferences

import io.github.stream29.kodex.cli.settings.MinimumSidebarWidthColumns
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.cli.settings.SubmitKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

public object DefaultApplicationPreferencesViewModelFactory : ApplicationPreferencesViewModelFactory {
    override fun create(
        dependencies: ApplicationPreferencesDependencies, ownerScope: CoroutineScope,
    ): ApplicationPreferencesViewModel = DefaultApplicationPreferencesViewModel(dependencies, ownerScope)
}

public fun createApplicationPreferencesViewModel(
    dependencies: ApplicationPreferencesDependencies, ownerScope: CoroutineScope,
): ApplicationPreferencesViewModel = DefaultApplicationPreferencesViewModelFactory.create(dependencies, ownerScope)

private class DefaultApplicationPreferencesViewModel(
    private val dependencies: ApplicationPreferencesDependencies,
    ownerScope: CoroutineScope,
) : ApplicationPreferencesViewModel {
    private val owner = Job(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + owner)
    private val mutable = MutableStateFlow(project())
    override val state: StateFlow<ApplicationPreferencesState> = mutable.asStateFlow()
    private val active: Boolean get() = owner.isActive && !mutable.value.closed

    init {
        scope.launch {
            combine(dependencies.widths, dependencies.newLineKey, dependencies.operationFailure) { _, _, _ -> Unit }
                .collect { if (active) mutable.value = project() }
        }
        owner.invokeOnCompletion { close() }
        if (!owner.isActive) close()
    }

    private fun project(): ApplicationPreferencesState {
        val widths = dependencies.widths.value
        return ApplicationPreferencesState(
            leftWidth = widths.first.coerceAtLeast(MinimumSidebarWidthColumns),
            rightWidth = widths.second.coerceAtLeast(MinimumSidebarWidthColumns),
            newLineKey = dependencies.newLineKey.value,
            operationFailure = dependencies.operationFailure.value,
        )
    }

    override fun setLeftWidth(columns: Int) {
        if (!active) return
        require(columns >= 0) { "Sidebar width must not be negative." }
        safely { dependencies.setLeftWidth(columns) }
    }
    override fun setRightWidth(columns: Int) {
        if (!active) return
        require(columns >= 0) { "Sidebar width must not be negative." }
        safely { dependencies.setRightWidth(columns) }
    }
    override fun setNewLineKey(newLineKey: NewLineKey) {
        if (!active) return
        safely {
            if (dependencies.setNewLineKey(newLineKey) == PreferencesWriteAdmission.Rejected) {
                dependencies.reportFailure(IllegalStateException("Input key admission rejected."))
            }
        }
    }
    override fun setSubmitKey(submitKey: SubmitKey) { setNewLineKey(submitKey.newLineKey) }
    private fun safely(action: () -> Unit) {
        try {
            action()
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
