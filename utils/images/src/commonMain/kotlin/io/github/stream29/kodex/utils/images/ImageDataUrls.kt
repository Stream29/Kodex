package io.github.stream29.kodex.utils.images

import kotlin.io.encoding.Base64

private const val DataUrlPrefix: String = "data:"

public actual fun String.decodePromptImageDataUrlBytes(
    maxInputBytes: Long,
): ByteArray {
    require(maxInputBytes >= 0) { "maxInputBytes must be non-negative" }

    val rest = this
        .takeIf { it.regionMatches(0, DataUrlPrefix, 0, DataUrlPrefix.length, ignoreCase = true) }
        ?.substring(DataUrlPrefix.length)
        ?: throw InvalidImageDataUrlException("missing data: prefix")

    val commaIndex = rest.indexOf(',')
    if (commaIndex < 0) {
        throw InvalidImageDataUrlException("missing comma separator")
    }

    val metadata = rest.substring(0, commaIndex)
    if (metadata.split(';').none { it.equals("base64", ignoreCase = true) }) {
        throw InvalidImageDataUrlException("only base64 data URLs are supported")
    }

    val encoded = rest.substring(commaIndex + 1)
    if (encoded.length.toLong() > maxInputBytes) {
        throw ImageInputTooLargeException("base64 payload", encoded.length.toLong(), maxInputBytes)
    }

    val bytes = try {
        Base64.decode(encoded)
    } catch (e: IllegalArgumentException) {
        throw InvalidImageDataUrlException("invalid base64 payload: ${e.message}")
    }
    if (bytes.size.toLong() > maxInputBytes) {
        throw ImageInputTooLargeException("decoded input", bytes.size.toLong(), maxInputBytes)
    }
    return bytes
}
