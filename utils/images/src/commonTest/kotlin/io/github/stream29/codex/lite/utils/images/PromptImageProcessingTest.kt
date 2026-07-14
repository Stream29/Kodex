package io.github.stream29.codex.lite.utils.images

import de.infix.testBalloon.framework.core.testSuite

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue



val promptImageProcessingTest by testSuite {
    test("plan prompt image preserves supported source bytes when no resize is needed") {
        val source = ImageInfo(ImageMimeType.Png, ImageDimensions(64, 32))
        val plan = source.planPromptImage(PromptImageMode.ResizeToFit)

        assertEquals(ImageDimensions(64, 32), plan.outputDimensions)
        assertEquals(ImageMimeType.Png, plan.outputMimeType)
        assertTrue(plan.preservesSourceBytes)
        assertFalse(plan.requiresTransformation)
    }

    test("plan prompt image requests resize for large images") {
        val source = ImageInfo(ImageMimeType.Png, ImageDimensions(4096, 2048))
        val plan = source.planPromptImage(PromptImageMode.ResizeToFit)

        assertEquals(ImageDimensions(2048, 1024), plan.outputDimensions)
        assertEquals(ImageMimeType.Png, plan.outputMimeType)
        assertFalse(plan.preservesSourceBytes)
        assertTrue(plan.requiresTransformation)
    }

    test("plan prompt image preserves supported output format when resize is needed") {
        val source = ImageInfo(ImageMimeType.Jpeg, ImageDimensions(4096, 2048))
        val plan = source.planPromptImage(PromptImageMode.ResizeToFit)

        assertEquals(ImageDimensions(2048, 1024), plan.outputDimensions)
        assertEquals(ImageMimeType.Jpeg, plan.outputMimeType)
        assertTrue(plan.requiresTransformation)
    }

    test("plan prompt image converts gif to png") {
        val source = ImageInfo(ImageMimeType.Gif, ImageDimensions(64, 32))
        val plan = source.planPromptImage(PromptImageMode.ResizeToFit)

        assertEquals(ImageDimensions(64, 32), plan.outputDimensions)
        assertEquals(ImageMimeType.Png, plan.outputMimeType)
        assertTrue(plan.requiresTransformation)
    }

    test("to prompt image returns encoded image when bytes can be preserved") {
        val bytes = pngBytes(64, 32)
        val image = bytes.toPromptImage(PromptImageMode.Original)

        assertEquals(ImageMimeType.Png, image.mimeType)
        assertEquals(ImageDimensions(64, 32), image.dimensions)
        assertContentEquals(bytes, image.bytes)
        assertTrue(image.toDataUrl().startsWith("data:image/png;base64,"))
    }

    test("to prompt image requires transformer for resize or transcode") {
        assertFailsWith<ImageTransformRequiredException> {
            pngBytes(4096, 2048).toPromptImage(PromptImageMode.ResizeToFit)
        }
        assertFailsWith<ImageTransformRequiredException> {
            gifBytes(64, 32).toPromptImage(PromptImageMode.ResizeToFit)
        }
    }

    test("to prompt image processes data urls") {
        val bytes = pngBytes(64, 32)
        val image = bytes.toDataUrl(ImageMimeType.Png).toPromptImage(PromptImageMode.Original)

        assertEquals(ImageMimeType.Png, image.mimeType)
        assertEquals(ImageDimensions(64, 32), image.dimensions)
        assertContentEquals(bytes, image.bytes)
    }
}
