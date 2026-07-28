package io.github.stream29.codex.lite.tool.viewimage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * @property detail Nullable because callers may omit image detail; `null`
 * means use the default high-detail representation.
 * @property environmentId Nullable because the local tool client only handles
 * the current environment; `null` means use the current environment.
 */
@Serializable
public data class ViewImageToolArguments(
    public val path: String,
    public val detail: ViewImageDetail? = null,
    @SerialName("environment_id")
    public val environmentId: String? = null,
)

@Serializable
public enum class ViewImageDetail {
    @SerialName("high")
    High,

    @SerialName("original")
    Original,
}

@Serializable
public data class ViewImageToolOutput(
    @SerialName("image_url")
    public val imageUrl: String,
    public val detail: ViewImageDetail,
)

public class ViewImageToolException(
    message: String,
) : IllegalArgumentException(message)
