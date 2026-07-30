package io.github.stream29.kodex.cli.settings.login

import io.github.stream29.kodex.cli.auth.KodexAuthStore
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

/** Current state of the short-lived browser sign-in popup. */
public sealed interface OpenAiLoginState {
    /** The popup has not started a browser sign-in attempt. */
    public data object Ready : OpenAiLoginState

    /** A local callback listener is being created before the browser opens. */
    public data object Preparing : OpenAiLoginState

    /** The browser has been asked to open and the local callback is waiting. */
    public data object WaitingForAuthorization : OpenAiLoginState

    /** The system did not accept the browser launch request. */
    public data object BrowserOpenFailed : OpenAiLoginState

    /** The OAuth code was exchanged and private credentials are ready. */
    public data object Completed : OpenAiLoginState

    /** The sign-in attempt did not complete. */
    public data object Failed : OpenAiLoginState
}

/** Coordinates a self-managed OpenAI browser sign-in without retaining the authorization URL in UI state. */
public class OpenAiLoginViewModel internal constructor(
    private val authStore: KodexAuthStore,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val mutableState = MutableStateFlow<OpenAiLoginState>(OpenAiLoginState.Ready)
    private val effectChannel = Channel<OpenAiLoginEffect>(Channel.BUFFERED)
    private var nextAttemptId = 1L
    private var activeLogin: ActiveLogin? = null
    private var loginJob: Job? = null

    /** Current popup state. The authorization URL is deliberately absent from this value. */
    public val state: StateFlow<OpenAiLoginState> = mutableState.asStateFlow()

    internal val effects: Flow<OpenAiLoginEffect> = effectChannel.receiveAsFlow()

    /** Creates a local callback listener and requests one browser launch. */
    public fun start() {
        if (loginJob?.isActive == true) return
        mutableState.value = OpenAiLoginState.Preparing
        loginJob = scope.launch {
            try {
                val attempt = authStore.startKodexLogin()
                val active = ActiveLogin(id = nextAttemptId++, attempt = attempt)
                activeLogin = active
                mutableState.value = OpenAiLoginState.WaitingForAuthorization
                effectChannel.send(
                    OpenAiLoginEffect.OpenExternalUrl(
                        attemptId = active.id,
                        url = attempt.authorizationUrl,
                    ),
                )
                attempt.awaitCompletion()
                if (activeLogin === active) {
                    activeLogin = null
                    mutableState.value = OpenAiLoginState.Completed
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Throwable) {
                activeLogin = null
                mutableState.value = OpenAiLoginState.Failed
            }
        }
    }

    /** Retries the system browser launcher while preserving the existing callback listener. */
    public fun retryBrowser() {
        val active = activeLogin ?: return
        if (mutableState.value != OpenAiLoginState.BrowserOpenFailed) return
        mutableState.value = OpenAiLoginState.WaitingForAuthorization
        scope.launch {
            effectChannel.send(
                OpenAiLoginEffect.OpenExternalUrl(
                    attemptId = active.id,
                    url = active.attempt.authorizationUrl,
                ),
            )
        }
    }

    /** Stops a pending login attempt without changing existing credentials. */
    public fun cancel() {
        activeLogin?.attempt?.cancel()
        activeLogin = null
        loginJob?.cancel()
        loginJob = null
        if (mutableState.value != OpenAiLoginState.Completed) {
            mutableState.value = OpenAiLoginState.Ready
        }
    }

    internal fun onBrowserOpened(attemptId: Long) {
        if (activeLogin?.id == attemptId && mutableState.value == OpenAiLoginState.BrowserOpenFailed) {
            mutableState.value = OpenAiLoginState.WaitingForAuthorization
        }
    }

    internal fun onBrowserOpenFailed(attemptId: Long) {
        if (activeLogin?.id == attemptId) {
            mutableState.value = OpenAiLoginState.BrowserOpenFailed
        }
    }

    internal fun isActive(attemptId: Long): Boolean = activeLogin?.id == attemptId

    override fun close() {
        cancel()
        effectChannel.close()
        scope.cancel()
    }
}

/** Creates an OpenAI login popup ViewModel bound to this scope's lifetime. */
public fun CoroutineScope.OpenAiLoginViewModel(authStore: KodexAuthStore): OpenAiLoginViewModel =
    OpenAiLoginViewModel(
        authStore = authStore,
        scope = supervisorChildScope(),
    )

internal sealed interface OpenAiLoginEffect {
    data class OpenExternalUrl(
        val attemptId: Long,
        val url: String,
    ) : OpenAiLoginEffect
}

private data class ActiveLogin(
    val id: Long,
    val attempt: KodexAuthLoginAttempt,
)
