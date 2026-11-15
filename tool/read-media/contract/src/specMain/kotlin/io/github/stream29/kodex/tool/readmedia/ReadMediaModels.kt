package io.github.stream29.kodex.tool.readmedia

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Arguments for the `read_media` model-direct tool.
 *
 * Relative [path] values are resolved against the current Agent working directory.
 * [detail] defaults to [MediaDetail.High] when `null`; [MediaDetail.Original]
 * requires model capability support (`canRequestOriginalImageDetail`).
 */
@Serializable
public data class ReadMediaToolArguments(
    public val path: String,
    public val detail: MediaDetail? = null,
)

@Serializable
public enum class MediaDetail {
    @SerialName("high")
    High,

    @SerialName("original")
    Original,
}

@Serializable
public data class ReadMediaToolOutput(
    @SerialName("image_url")
    public val imageUrl: String,
    public val detail: MediaDetail,
)

public class ReadMediaToolException(
    message: String,
) : IllegalArgumentException(message)
