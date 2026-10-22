@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.utils.images.codec

import io.github.stream29.kodex.utils.images.EncodedImage
import io.github.stream29.kodex.utils.images.PromptImageTransformRequest
import io.github.stream29.kodex.utils.images.PromptImageTransformer

/**
 * Stateless prompt-image transformer backed by KorIM's pure Kotlin PNG/GIF codecs.
 */
public expect object KorimPromptImageTransformer : PromptImageTransformer {
    public override suspend fun transform(request: PromptImageTransformRequest): EncodedImage
}
