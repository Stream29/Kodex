package io.github.stream29.kodex.app.settings

import io.github.stream29.kodex.app.settings.contract.OpenAiLoginEffect
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginDependencies
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginState
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginViewModel
import io.github.stream29.kodex.cli.auth.KodexAuthLoginAttempt
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Coordinates one short-lived OpenAI browser sign-in attempt. */
internal class OpenAiLoginViewModelImpl(
    private val dependencies: OpenAiLoginDependencies,
    private val scope: CoroutineScope,
) : OpenAiLoginViewModel {
    private val mutableState = MutableStateFlow<OpenAiLoginState>(OpenAiLoginState.Ready)
    private val effectChannel = Channel<OpenAiLoginEffect>(Channel.BUFFERED)
    private var nextAttemptId: Long = 1L
    private var pendingAttemptId: Long? = null
    private var activeLogin: ActiveLogin? = null
    private var loginJob: Job? = null
    private var closed: Boolean = false

    override val state: StateFlow<OpenAiLoginState> = mutableState.asStateFlow()
    override val effects: Flow<OpenAiLoginEffect> = effectChannel.receiveAsFlow()

    override fun start() {
        if (closed || loginJob?.isActive == true) return
        val attemptId = allocateAttemptId()
        pendingAttemptId = attemptId
        mutableState.value = OpenAiLoginState.Preparing
        loginJob = scope.launch {
            try {
                val attempt = dependencies.startLogin()
                if (closed || pendingAttemptId != attemptId) {
                    attempt.cancel()
                    return@launch
                }
                val active = ActiveLogin(
                    id = attemptId,
                    attempt = attempt,
                )
                activeLogin = active
                mutableState.value = OpenAiLoginState.WaitingForAuthorization(attemptId)
                effectChannel.send(
                    OpenAiLoginEffect.OpenExternalUrl(
                        attemptId = attemptId,
                        url = attempt.authorizationUrl,
                    ),
                )
                attempt.awaitCompletion()
                if (
                    !closed &&
                    pendingAttemptId == attemptId &&
                    activeLogin === active
                ) {
                    pendingAttemptId = null
                    activeLogin = null
                    mutableState.value = OpenAiLoginState.Completed
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                if (!closed && pendingAttemptId == attemptId) {
                    pendingAttemptId = null
                    activeLogin = null
                    mutableState.value = OpenAiLoginState.Failed(failure.loginFailureMessage())
                }
            }
        }
    }

    override fun retryBrowser(attemptId: Long) {
        if (closed) return
        val active = activeLogin?.takeIf { candidate -> candidate.id == attemptId } ?: return
        val failed = mutableState.value as? OpenAiLoginState.BrowserOpenFailed ?: return
        if (failed.attemptId != attemptId) return
        mutableState.value = OpenAiLoginState.WaitingForAuthorization(attemptId)
        scope.launch {
            effectChannel.send(
                OpenAiLoginEffect.OpenExternalUrl(
                    attemptId = attemptId,
                    url = active.attempt.authorizationUrl,
                ),
            )
        }
    }

    override fun cancel() {
        pendingAttemptId = null
        activeLogin?.attempt?.cancel()
        activeLogin = null
        loginJob?.cancel()
        loginJob = null
        if (mutableState.value != OpenAiLoginState.Completed) {
            mutableState.value = OpenAiLoginState.Ready
        }
    }

    override fun onBrowserOpened(attemptId: Long) {
        if (closed || activeLogin?.id != attemptId) return
        val failed = mutableState.value as? OpenAiLoginState.BrowserOpenFailed ?: return
        if (failed.attemptId == attemptId) {
            mutableState.value = OpenAiLoginState.WaitingForAuthorization(attemptId)
        }
    }

    override fun onBrowserOpenFailed(attemptId: Long) {
        if (closed || activeLogin?.id != attemptId) return
        val waiting = mutableState.value as? OpenAiLoginState.WaitingForAuthorization ?: return
        if (waiting.attemptId == attemptId) {
            mutableState.value = OpenAiLoginState.BrowserOpenFailed(attemptId)
        }
    }

    override fun isActive(attemptId: Long): Boolean =
        !closed && activeLogin?.id == attemptId

    override fun close() {
        if (closed) return
        cancel()
        closed = true
        scope.cancel()
        effectChannel.close()
    }

    private fun allocateAttemptId(): Long {
        check(nextAttemptId < Long.MAX_VALUE) {
            "OpenAI login attempt ids are exhausted."
        }
        return nextAttemptId++
    }
}

/** Uses the same browser/revision UI with a frontend-owned RPC login attempt. */
public fun createOpenAiLoginViewModel(
    ownerScope: CoroutineScope,
    startLogin: suspend () -> KodexAuthLoginAttempt,
): OpenAiLoginViewModel =
    createOpenAiLoginViewModel(OpenAiLoginDependencies { startLogin() }, ownerScope)

/** Constructs the component using its declared dependency port and child lifecycle. */
public fun createOpenAiLoginViewModel(
    dependencies: OpenAiLoginDependencies,
    ownerScope: CoroutineScope,
): OpenAiLoginViewModel =
    OpenAiLoginViewModelImpl(dependencies, ownerScope.supervisorChildScope())

private data class ActiveLogin(
    val id: Long,
    val attempt: KodexAuthLoginAttempt,
)

private fun Throwable.loginFailureMessage(): String =
    message?.trim()?.takeIf(String::isNotEmpty)
        ?: "OpenAI sign-in failed."
