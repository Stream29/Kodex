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
