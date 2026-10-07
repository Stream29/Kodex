package io.github.stream29.kodex.app.settings.contract

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Atomic render state of a short-lived browser sign-in component.
 *
 * The renderer branches on the variant, never on auth-store state or a URL:
 * Ready offers start/cancel; Preparing and Waiting show progress/cancel;
 * BrowserOpenFailed offers browser retry for its exact attempt plus cancel;
 * Failed displays the safe message and offers a fresh attempt or cancel;
 * Completed shows success and offers close, without automatically dismissing.
 * Layout, focus, theme, wording and keyboard bindings belong to the renderer.
 */
public sealed interface OpenAiLoginState {
    /** No active attempt; the renderer offers browser sign-in. */
    public data object Ready : OpenAiLoginState

    /** Preparation is in flight; repeated start must not create another attempt. */
    public data object Preparing : OpenAiLoginState

    /**
     * An attempt is waiting for authorization or its completion.
     * This state does not imply that the host successfully opened a browser.
     * @throws IllegalArgumentException if the attempt id is not positive.
     */
    public data class WaitingForAuthorization(
        public val attemptId: Long,
    ) : OpenAiLoginState {
        init {
            require(attemptId > 0) { "An OpenAI login attempt id must be positive." }
        }
    }

    /**
     * Browser launch failed; authorization is still pending on the same attempt.
     * @throws IllegalArgumentException if the attempt id is not positive.
     */
    public data class BrowserOpenFailed(
        public val attemptId: Long,
    ) : OpenAiLoginState {
        init {
            require(attemptId > 0) { "An OpenAI login attempt id must be positive." }
        }
    }

    /** The dependency completed authorization and credential commit, not just browser launch. */
    public data object Completed : OpenAiLoginState

    /**
     * Preparation or authorization failed; a new start is required to retry.
     * @throws IllegalArgumentException if the display message is blank.
     */
    public data class Failed(
        public val message: String,
    ) : OpenAiLoginState {
        init {
            require(message.isNotBlank()) { "An OpenAI login failure message must not be blank." }
        }
    }
}

/**
 * One-shot host output consumed by one collector per component.
 *
 * URLs are transient interaction data: never mirror them into persistent
 * state, logs, global effect streams or credential settings. Before acting
 * on a delayed output, the host checks [OpenAiLoginViewModel.isActive].
 */
public sealed interface OpenAiLoginEffect {
    /**
     * Open this attempt's authorization URL and report launch success/failure
     * with the same id. Either launch result leaves authorization pending.
     * @throws IllegalArgumentException if the id is not positive or the URL is blank.
     */
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

/**
 * Component-owned browser sign-in state machine using [OpenAiLoginDependencies].
 *
 * Construction is lazy and starts Ready. Composition supplies an owner scope
 * with a serial execution context; commands and host callbacks must run on
 * that same context. State and attempt identity are owned here, not by the
 * renderer or parent. Only one preparation/authorization job is active at a
 * time. Completion from a cancelled/replaced attempt cannot update the child.
 *
 * The owner closes the child on dismissal. Cancellation/closure stops local
 * work and cancels the exact attempt without changing the credential source
 * or undoing a committed sign-in. Parent-scope cancellation stops child work;
 * the owner must still close the child to release its effect stream/handle.
 */
public interface OpenAiLoginViewModel : AutoCloseable {
    /** Atomic snapshot; no URL, callback or credential material is included. */
    public val state: StateFlow<OpenAiLoginState>

    /** One-shot outputs, not a broadcast/replay of state. Closed on disposal. */
    public val effects: Flow<OpenAiLoginEffect>

    /**
     * Starts preparation and publishes Preparing if no login job is active.
     * Repeated calls during an active job and calls after close are no-ops.
     * Each new attempt uses a monotonically increasing component-local id,
     * distinct from backend OAuth ids. Safe dependency failures become Failed;
     * empty exception messages use a generic sign-in failure message.
     *
     * @throws IllegalStateException if another attempt id cannot be represented.
     */
    public fun start(): Unit

    /**
     * Re-emits the URL only if BrowserOpenFailed and the active handle both
     * match [attemptId], then returns to WaitingForAuthorization.
     * Does not prepare a new login, change id/source, or resend a callback.
     * Stale ids and all other states are no-ops.
     */
    public fun retryBrowser(attemptId: Long): Unit

    /**
     * Invalidates pending outputs, cancels preparation and the exact active
     * handle, and returns to Ready. If already Completed, preserves success.
     * Does not dismiss the parent popup or roll back committed credentials.
     * Repeated calls are safe; after close this has no observable effect.
     */
    public fun cancel(): Unit

    /**
     * Acknowledges browser launch for the exact active id.
     * Restores Waiting only from BrowserOpenFailed for that id; stale reports
     * or other states are ignored. Never implies authentication completion.
     */
    public fun onBrowserOpened(attemptId: Long): Unit

    /**
     * Publishes BrowserOpenFailed only from Waiting for the exact active id.
     * Keeps the same authorization handle pending; stale reports are ignored.
     */
    public fun onBrowserOpenFailed(attemptId: Long): Unit

    /** True only for the current delivered handle, false after cancel/completion/close. */
    public fun isActive(attemptId: Long): Boolean

    /**
     * Cancels the child and its exact attempt, then closes effects.
     * Does not cancel the parent scope or auth backend. Idempotent; subsequent
     * commands and callbacks are no-ops and no new effects are produced.
     */
    override fun close(): Unit
}

/**
 * Bound factory for a lazy, independently disposable login popup child.
 *
 * Composition binds [OpenAiLoginDependencies] and the owner lifecycle before
 * constructing the child, including its credential destination. The factory
 * must not initiate login until the returned child's start command.
 */
public fun interface OpenAiLoginViewModelFactory {
    public fun create(): OpenAiLoginViewModel
}
