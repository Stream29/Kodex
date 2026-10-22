package io.github.stream29.kodex.utils.externalurl

/**
 * Requests that the client system open [url] with its registered external URL handler.
 *
 * A [OpenExternalUrlResult.Started] result only confirms that the client URL launcher
 * accepted the request; it does not confirm that the destination application loaded the URL.
 *
 * Blank input is rejected before any client launcher is invoked. Ordinary launcher
 * failure is returned without including the URL, which may contain credentials.
 * Cancellation is propagated to the caller.
 *
 * @throws kotlinx.coroutines.CancellationException if the caller is cancelled.
 */
public expect suspend fun openExternalUrl(url: String): OpenExternalUrlResult

/** Result of requesting that the client system open an external URL. */
public sealed interface OpenExternalUrlResult {
    /** The client URL launcher accepted the request. */
    public data object Started : OpenExternalUrlResult

    /** The client URL launcher could not accept the request. */
    public data class Failed(
        public val message: String,
    ) : OpenExternalUrlResult
}
