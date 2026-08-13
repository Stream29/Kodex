package io.github.stream29.kodex.app.settings.contract

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Current state of one short-lived browser sign-in ViewModel. */
public sealed interface OpenAiLoginState {
    public data object Ready : OpenAiLoginState
    public data object Preparing : OpenAiLoginState

    public data class WaitingForAuthorization(
        public val attemptId: Long,
    ) : OpenAiLoginState {
        init {
            require(attemptId > 0) { "An OpenAI login attempt id must be positive." }
        }
    }

    public data class BrowserOpenFailed(
        public val attemptId: Long,
    ) : OpenAiLoginState {
        init {
            require(attemptId > 0) { "An OpenAI login attempt id must be positive." }
        }
    }

    public data object Completed : OpenAiLoginState

    public data class Failed(
        public val message: String,
    ) : OpenAiLoginState {
        init {
            require(message.isNotBlank()) { "An OpenAI login failure message must not be blank." }
        }
    }
}

/**
 * One-shot host effect.
 *
 * Authorization URLs are deliberately absent from persistent state snapshots.
 */
public sealed interface OpenAiLoginEffect {
    public data class OpenExternalUrl(
        public val attemptId: Long,
        public val url: String,
    ) : OpenAiLoginEffect {
        init {
            require(attemptId > 0) { "An OpenAI login attempt id must be positive." }
            require(url.isNotBlank()) { "An OpenAI authorization URL must not be blank." }
        }
    }
}

/** Browser sign-in contract independent of its auth-store implementation. */
public interface OpenAiLoginViewModel : AutoCloseable {
    public val state: StateFlow<OpenAiLoginState>
    public val effects: Flow<OpenAiLoginEffect>

    public fun start(): Unit

    /** Retries the browser effect only for the still-active [attemptId]. */
    public fun retryBrowser(attemptId: Long): Unit

    public fun cancel(): Unit

    public fun onBrowserOpened(attemptId: Long): Unit

    public fun onBrowserOpenFailed(attemptId: Long): Unit

    /** Guards a delayed host effect against a cancelled or replaced attempt. */
    public fun isActive(attemptId: Long): Boolean

    override fun close(): Unit
}

/** Creates one independently disposable login popup ViewModel. */
public fun interface OpenAiLoginViewModelFactory {
    public fun create(): OpenAiLoginViewModel
}
