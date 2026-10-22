package io.github.stream29.kodex.utils.externalurl

public actual suspend fun openExternalUrl(url: String): OpenExternalUrlResult {
    if (url.isBlank()) return OpenExternalUrlResult.Failed("The URL must not be blank.")
    return openExternalUrlOnHost(url)
}

internal expect suspend fun openExternalUrlOnHost(url: String): OpenExternalUrlResult
