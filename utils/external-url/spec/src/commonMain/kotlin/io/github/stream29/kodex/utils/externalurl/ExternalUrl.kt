package io.github.stream29.kodex.utils.externalurl

/** Result of requesting that the host system open an external URL. */
public sealed interface OpenExternalUrlResult {
    /** The host URL launcher accepted the request. */
    public data object Started : OpenExternalUrlResult

    /** The host URL launcher could not accept the request. */
    public data class Failed(
        public val message: String,
    ) : OpenExternalUrlResult
}

/**
 * Requests opening URLs through the host's registered handler.
 *
 * This boundary reports launcher acceptance, not whether a browser finished
 * loading the destination. It does not own the destination application's
 * lifetime.
 */
public fun interface ExternalUrlOpener {
    /**
     * Opens [url], returning [OpenExternalUrlResult.Failed] for blank input or
     * when the launcher cannot be started or rejects the request.
     *
     * Cancellation propagates rather than being converted into a failed result.
     */
    public suspend fun open(url: String): OpenExternalUrlResult
}
