package io.github.stream29.kodex.app.settings.contract

import io.github.stream29.kodex.cli.auth.KodexAuthLoginAttempt
import kotlinx.coroutines.CancellationException

/**
 * Capability required by one OpenAI Login component.
 *
 * Bind the credential destination when composing the child. The component
 * neither reads settings to choose a source nor knows token, callback, RPC
 * or persistence protocols. Opening a browser is a separate renderer/host
 * responsibility driven by [OpenAiLoginEffect], not by this dependency.
 */
public fun interface OpenAiLoginDependencies {
    /**
     * Prepares one fresh login attempt and transfers its handle to the child.
     *
     * Return a non-blank authorization URL for the fixed destination.
     * [KodexAuthLoginAttempt.awaitCompletion] succeeds only after authorization
     * and the destination's credential commit complete; browser launch alone
     * is not success. The dependency owns listener cleanup/pending release on success,
     * failure and cancellation. Explicitly cancelling an attempt must be safe after it
     * has already completed and must not delete or roll back credentials.
     *
     * Failures from preparation or completion are converted to
     * [OpenAiLoginState.Failed]. Exception messages must be safe for display,
     * without tokens, authorization URLs or callback data. Cancellation stays
     * cancellation, not an error message. A handle delivered after an explicit user cancel
     * is explicitly cancelled; after disposal/replacement it is released instead of used.
     *
     * @throws CancellationException when preparation is cancelled.
     */
    public suspend fun startLogin(): KodexAuthLoginAttempt
}
