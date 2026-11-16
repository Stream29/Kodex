package io.github.stream29.kodex.utils.images.codec

import de.infix.testBalloon.framework.core.TestSuiteScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.images.ImageDimensions
import io.github.stream29.kodex.utils.images.ImageMimeType
import io.github.stream29.kodex.utils.images.PromptImageMode
import io.github.stream29.kodex.utils.images.PromptImagePlan
import io.github.stream29.kodex.utils.images.PromptImageTransformRequest
import io.github.stream29.kodex.utils.images.PromptImageTransformer
import io.github.stream29.kodex.utils.images.detectImageInfo
import io.github.stream29.kodex.utils.images.requireImageInfo
import io.github.stream29.kodex.utils.images.toPromptImage
import kotlin.test.assertEquals

private fun TestSuiteScope.promptImageTransformerContract(transformer: PromptImageTransformer) {
    test("resizes png images") {
        val source = promptImagePng64x32.requireImageInfo()
        val image = transformer.transform(
            PromptImageTransformRequest(
                sourceBytes = promptImagePng64x32,
                plan = PromptImagePlan(
                    source = source,
                    outputDimensions = ImageDimensions(32, 16),
                    outputMimeType = ImageMimeType.Png,
                    preservesSourceBytes = false,
                ),
            ),
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
        val source = promptImageJpeg64x32.requireImageInfo()
        val image = transformer.transform(
            PromptImageTransformRequest(
                sourceBytes = promptImageJpeg64x32,
                plan = PromptImagePlan(
                    source = source,
                    outputDimensions = ImageDimensions(32, 16),
                    outputMimeType = ImageMimeType.Jpeg,
                    preservesSourceBytes = false,
                ),
            ),
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
