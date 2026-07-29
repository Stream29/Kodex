package io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One question in a pending user-input request. */
@Serializable
public data class PendingRequestUserInputQuestion(
    public val id: String,
    public val header: String,
    public val question: String,
    @SerialName("allows_other")
    public val allowsOther: Boolean = false,
    @SerialName("is_secret")
    public val isSecret: Boolean = false,
    public val options: List<PendingRequestUserInputOption>? = null,
)

/** One selectable answer in a pending user-input request. */
@Serializable
public data class PendingRequestUserInputOption(
    public val label: String,
    public val description: String,
)
