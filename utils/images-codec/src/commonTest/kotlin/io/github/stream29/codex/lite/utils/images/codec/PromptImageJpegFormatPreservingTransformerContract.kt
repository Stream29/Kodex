package io.github.stream29.codex.lite.utils.images.codec

import io.github.stream29.codex.lite.utils.images.ImageDimensions
import io.github.stream29.codex.lite.utils.images.ImageMimeType
import io.github.stream29.codex.lite.utils.images.PromptImageMode
import io.github.stream29.codex.lite.utils.images.PromptImageResizeLimits
import io.github.stream29.codex.lite.utils.images.PromptImageTransformer
import io.github.stream29.codex.lite.utils.images.detectImageInfo
import io.github.stream29.codex.lite.utils.images.toPromptImage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

abstract class PromptImageJpegFormatPreservingTransformerContract {
    protected abstract val transformer: PromptImageTransformer

    @Test
    fun resizesJpegImagesAsJpeg() = runTest {
        val image = promptImageJpeg64x32.toPromptImage(
            mode = PromptImageMode.ResizeWithLimits(
                PromptImageResizeLimits(maxDimension = 32, maxPatches = 10_000),
            ),
            transformer = transformer,
        )

        assertEquals(ImageMimeType.Jpeg, image.mimeType)
        assertEquals(ImageDimensions(32, 16), image.dimensions)
        assertEquals(ImageMimeType.Jpeg, image.bytes.detectImageInfo()?.mimeType)
        assertEquals(ImageDimensions(32, 16), image.bytes.detectImageInfo()?.dimensions)
    }
}
