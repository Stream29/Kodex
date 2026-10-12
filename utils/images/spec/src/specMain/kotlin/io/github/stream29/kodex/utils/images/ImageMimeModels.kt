package io.github.stream29.kodex.utils.images

public enum class ImageMimeType(
    public val mime: String,
) {
    Png("image/png"),
    Jpeg("image/jpeg"),
    Gif("image/gif"),
}

/**
 * Unknown or unsupported image byte stream.
 */
public class UnsupportedImageFormatException :
    IllegalArgumentException("Unsupported image format")
