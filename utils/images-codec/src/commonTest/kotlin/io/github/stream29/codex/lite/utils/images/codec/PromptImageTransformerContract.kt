package io.github.stream29.codex.lite.utils.images.codec

import de.infix.testBalloon.framework.core.TestSuiteScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.utils.images.ImageDimensions
import io.github.stream29.codex.lite.utils.images.ImageMimeType
import io.github.stream29.codex.lite.utils.images.PromptImageMode
import io.github.stream29.codex.lite.utils.images.PromptImageResizeLimits
import io.github.stream29.codex.lite.utils.images.PromptImageTransformer
import io.github.stream29.codex.lite.utils.images.detectImageInfo
import io.github.stream29.codex.lite.utils.images.toPromptImage
import kotlin.test.assertEquals

private fun TestSuiteScope.promptImageTransformerContract(transformer: PromptImageTransformer) {
    test("resizes png images") {
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

    test("transcodes gif images to png") {
        val image = promptImageGif64x32.toPromptImage(
            mode = PromptImageMode.ResizeToFit,
            transformer = transformer,
        )

        assertEquals(ImageMimeType.Png, image.mimeType)
        assertEquals(ImageDimensions(64, 32), image.dimensions)
        assertEquals(ImageMimeType.Png, image.bytes.detectImageInfo()?.mimeType)
    }
}

private fun TestSuiteScope.jpegFormatPreservingTransformerContract(transformer: PromptImageTransformer) {
    test("resizes jpeg images as jpeg") {
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

val korimPromptImageTransformerContractTest by testSuite {
    promptImageTransformerContract(KorimPromptImageTransformer)
}

val hostPromptImageTransformerContractTest by testSuite {
    promptImageTransformerContract(HostPromptImageTransformer)
}

val hostPromptImageTransformerJpegFormatPreservingContractTest by testSuite {
    jpegFormatPreservingTransformerContract(HostPromptImageTransformer)
}
