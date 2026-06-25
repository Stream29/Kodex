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

abstract class PromptImageTransformerContract {
    protected abstract val transformer: PromptImageTransformer

    @Test
    fun resizesPngImages() = runTest {
        val image = promptImagePng64x32.toPromptImage(
            mode = PromptImageMode.ResizeWithLimits(
                PromptImageResizeLimits(maxDimension = 32, maxPatches = 10_000),
            ),
            transformer = transformer,
        )

        assertEquals(ImageMimeType.Png, image.mimeType)
        assertEquals(ImageDimensions(32, 16), image.dimensions)
        assertEquals(ImageDimensions(32, 16), image.bytes.detectImageInfo()?.dimensions)
    }

    @Test
    fun transcodesGifImagesToPng() = runTest {
        val image = promptImageGif64x32.toPromptImage(
            mode = PromptImageMode.ResizeToFit,
            transformer = transformer,
        )

        assertEquals(ImageMimeType.Png, image.mimeType)
        assertEquals(ImageDimensions(64, 32), image.dimensions)
        assertEquals(ImageMimeType.Png, image.bytes.detectImageInfo()?.mimeType)
    }
}

class KorimPromptImageTransformerContractTest : PromptImageTransformerContract() {
    override val transformer: PromptImageTransformer
        get() = KorimPromptImageTransformer
}

class HostPromptImageTransformerContractTest : PromptImageTransformerContract() {
    override val transformer: PromptImageTransformer
        get() = HostPromptImageTransformer
}

class HostPromptImageTransformerJpegFormatPreservingContractTest :
    PromptImageJpegFormatPreservingTransformerContract() {
    override val transformer: PromptImageTransformer
        get() = HostPromptImageTransformer
}
