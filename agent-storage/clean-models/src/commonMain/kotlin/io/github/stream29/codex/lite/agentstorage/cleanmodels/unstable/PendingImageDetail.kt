package io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Requested image detail while `view_image` is pending. */
@Serializable
public enum class PendingImageDetail {
    @SerialName("high")
    High,

    @SerialName("original")
    Original,
}
