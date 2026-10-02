package io.github.stream29.kodex.app.authenticationsettings

import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationOperation
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationOperationState
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Dependency-only factory; owner cancellation closes only this child. */
public object DefaultAuthenticationSettingsViewModelFactory : AuthenticationSettingsViewModelFactory {
    override fun create(
        dependencies: AuthenticationSettingsDependencies,
        ownerScope: CoroutineScope,
    ): AuthenticationSettingsViewModel = DefaultAuthenticationSettingsViewModel(dependencies, ownerScope)
}

/** Observe shared summaries without creating an OAuth client or executing an initial command. */
public fun createAuthenticationSettingsViewModel(
    dependencies: AuthenticationSettingsDependencies,
    ownerScope: CoroutineScope,
): AuthenticationSettingsViewModel = DefaultAuthenticationSettingsViewModelFactory.create(dependencies, ownerScope)

private class DefaultAuthenticationSettingsViewModel(
    private val dependencies: AuthenticationSettingsDependencies,
    ownerScope: CoroutineScope,
) : AuthenticationSettingsViewModel {
    private val owner = Job(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + owner)
    private val mutable = MutableStateFlow(AuthenticationSettingsState(
        selectedSource = dependencies.selectedSource.value,
        authentication = dependencies.authentication.value,
        operationFailure = dependencies.operationFailure.value,
    ))
    override val state: StateFlow<AuthenticationSettingsState> = mutable.asStateFlow()
    private val active: Boolean get() = owner.isActive && !mutable.value.closed

    init {
        scope.launch {
            dependencies.selectedSource.collect { mutable.value = mutable.value.copy(selectedSource = it) }
        }
        scope.launch {
            dependencies.authentication.collect { mutable.value = mutable.value.copy(authentication = it) }
        }
        scope.launch {
            dependencies.operationFailure.collect { mutable.value = mutable.value.copy(operationFailure = it) }
        }
        owner.invokeOnCompletion { close() }
        if (!owner.isActive) close()
    }

    override fun updateSource(source: KodexAuthSource): Boolean {
        if (!active) return false
        return try {
            dependencies.updateSource(source)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            dependencies.reportFailure(failure)
            false
        }
    }

    override fun requestLogin() {
        if (!active) return
        try {
            dependencies.openLogin()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            dependencies.reportFailure(failure)
        }
    }

    override fun requestLogout() {
        if (!active || mutable.value.operation == SettingsAuthenticationOperationState.SigningOut) return
        mutable.value = mutable.value.copy(confirmation = AuthenticationLogoutConfirmation())
    }

    override fun confirmLogout(expected: AuthenticationLogoutConfirmation) {
        if (!active || mutable.value.confirmation !== expected ||
            mutable.value.operation == SettingsAuthenticationOperationState.SigningOut) return
        val source = dependencies.selectedSource.value
        mutable.value = mutable.value.copy(
            selectedSource = source,
            confirmation = null,
            operation = SettingsAuthenticationOperationState.SigningOut,
        )
        scope.launch {
            try {
                dependencies.remove(source)
                if (active) mutable.value = mutable.value.copy(operation = SettingsAuthenticationOperationState.Idle)
            } catch (cancelled: CancellationException) {
                if (active) mutable.value = mutable.value.copy(operation = SettingsAuthenticationOperationState.Idle)
                throw cancelled
            } catch (failure: Throwable) {
                if (active) {
                    mutable.value = mutable.value.copy(operation =
                        SettingsAuthenticationOperationState.Failed(SettingsAuthenticationOperation.Logout))
                }
                dependencies.reportFailure(failure)
            }
        }
    }

    override fun cancelLogout(expected: AuthenticationLogoutConfirmation) {
        if (active && mutable.value.confirmation === expected) {
            mutable.value = mutable.value.copy(confirmation = null)
        }
    }

    override fun hidePage() {
        if (active) mutable.value = mutable.value.copy(confirmation = null)
    }

    override fun dismissFailure() {
        if (!active) return
        if (mutable.value.operation is SettingsAuthenticationOperationState.Failed) {
            mutable.value = mutable.value.copy(operation = SettingsAuthenticationOperationState.Idle)
        }
        dependencies.dismissFailure()
    }

    override fun close() {
        if (mutable.value.closed) return
        mutable.value = mutable.value.copy(confirmation = null, closed = true)
        owner.cancel()
    }
}
