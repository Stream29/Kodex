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
 * Requests that the host system open [url] with its registered external URL handler.
 *
 * A [OpenExternalUrlResult.Started] result only confirms that the host URL launcher
 * accepted the request; it does not confirm that the destination application loaded the URL.
 */
public suspend fun openExternalUrl(url: String): OpenExternalUrlResult {
    if (url.isBlank()) return OpenExternalUrlResult.Failed("The URL must not be blank.")
    return openExternalUrlOnHost(url)
}

internal expect suspend fun openExternalUrlOnHost(url: String): OpenExternalUrlResult
