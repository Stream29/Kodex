package io.github.stream29.kodex.utils.images

import kotlin.io.encoding.Base64

/**
 * Wraps image bytes in a base64 data URL without decoding or validating them.
 */
public fun ByteArray.toDataUrl(mime: String): String =
    "data:$mime;base64,${Base64.encode(this)}"

/**
 * Wraps image bytes in a base64 data URL without decoding or validating them.
 */
public fun ByteArray.toDataUrl(mimeType: ImageMimeType): String =
    toDataUrl(mimeType.mime)
