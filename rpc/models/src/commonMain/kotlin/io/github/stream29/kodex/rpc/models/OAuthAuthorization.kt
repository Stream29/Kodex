package io.github.stream29.kodex.rpc.models

import kotlinx.serialization.Serializable

/**
 * Prepared login shared by both OAuth targets.
 *
 * The id is unique within the connected backend's lifetime, not a durable credential handle.
 * The URL is transient authorization data for the frontend browser, not a setting or log value.
 */
@Serializable
public data class OAuthAuthorization(
    public val attemptId: Long,
    public val url: String,
) {
    init {
        require(attemptId > 0) { "An OAuth attempt id must be positive." }
        require(url.isNotBlank()) { "An OAuth authorization URL must not be blank." }
    }
}
