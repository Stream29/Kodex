package io.github.stream29.kodex.utils.externalurl

/**
 * Requests that the host system open [url] with its registered external URL handler.
 *
 * A [OpenExternalUrlResult.Started] result only confirms that the host URL launcher
 * accepted the request; it does not confirm that the destination application loaded the URL.
 *
 * Blank input is rejected before any host launcher is invoked. Ordinary launcher
 * failure is returned without including the URL, which may contain credentials.
 * Cancellation is propagated to the caller.
 *
 * @throws kotlinx.coroutines.CancellationException if the caller is cancelled.
 */
public suspend fun openExternalUrl(url: String): OpenExternalUrlResult {
    if (url.isBlank()) return OpenExternalUrlResult.Failed("The URL must not be blank.")
    return openExternalUrlOnHost(url)
}

internal expect suspend fun openExternalUrlOnHost(url: String): OpenExternalUrlResult
