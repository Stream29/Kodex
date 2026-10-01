package io.github.stream29.kodex.utils.externalurl

/**
 * Requests that the host system open [url] with its registered external URL handler.
 *
 * A [OpenExternalUrlResult.Started] result only confirms that the host URL launcher
 * accepted the request; it does not confirm that the destination application loaded the URL.
 */
public suspend fun openExternalUrl(url: String): OpenExternalUrlResult =
    HostExternalUrlOpener.open(url)

/** Platform URL launcher; the destination application's lifetime is not owned. */
public object HostExternalUrlOpener : ExternalUrlOpener {
    override suspend fun open(url: String): OpenExternalUrlResult {
        if (url.isBlank()) return OpenExternalUrlResult.Failed("The URL must not be blank.")
        return openExternalUrlOnHost(url)
    }
}

internal expect suspend fun openExternalUrlOnHost(url: String): OpenExternalUrlResult
