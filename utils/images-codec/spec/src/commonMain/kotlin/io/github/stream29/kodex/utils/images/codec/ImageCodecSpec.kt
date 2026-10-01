package io.github.stream29.kodex.utils.images.codec

import io.github.stream29.kodex.utils.images.EncodedImage
import io.github.stream29.kodex.utils.images.PromptImageTransformRequest

/**
 * Codec boundary for an image transformation requested by a prompt-image
 * policy. Implementations may use JVM, Node.js, Skiko, GDI+, or a pure Kotlin
 * codec, but must preserve the requested output metadata.
 */
public fun interface PromptImageCodec {
    /**
     * Transforms one encoded image.
     *
     * @throws ImageCodecException if decoding or encoding fails.
     * @throws UnsupportedImageCodecException if the implementation cannot
     * decode or encode the requested MIME type.
     */
    public suspend fun transform(request: PromptImageTransformRequest): EncodedImage
}
