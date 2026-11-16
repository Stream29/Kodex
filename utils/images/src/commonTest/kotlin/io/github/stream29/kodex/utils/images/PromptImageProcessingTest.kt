package io.github.stream29.kodex.utils.images

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
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

    test("encoded images and transform requests copy input and getter arrays") {
        val input = pngBytes(64, 32)
        val expected = input.copyOf()
        val plan = input.requireImageInfo().planPromptImage(PromptImageMode.ResizeToFit)
        val image = EncodedImage(input, ImageMimeType.Png, ImageDimensions(64, 32))
        val request = PromptImageTransformRequest(input, plan)
        input.fill(0)
        image.bytes.fill(1)
        request.sourceBytes.fill(2)
        assertContentEquals(expected, image.bytes)
        assertContentEquals(expected, request.sourceBytes)
        assertTrue(image.toDataUrl().startsWith("data:image/png;base64,"))
    }

    test("unneeded transformer is skipped and transformed metadata must match the plan") {
        val small = pngBytes(64, 32)
        val unused = PromptImageTransformer { error("No transformation is needed") }
        assertContentEquals(small, small.toPromptImage(PromptImageMode.Original, unused).bytes)
        val large = pngBytes(4096, 2048)
        val wrongMime = PromptImageTransformer { request ->
            EncodedImage(byteArrayOf(), ImageMimeType.Jpeg, request.plan.outputDimensions)
        }
        val wrongDimensions = PromptImageTransformer { request ->
            EncodedImage(byteArrayOf(), request.plan.outputMimeType, ImageDimensions(1, 1))
        }
        assertFailsWith<IllegalArgumentException> { large.toPromptImage(PromptImageMode.ResizeToFit, wrongMime) }
        assertFailsWith<IllegalArgumentException> { large.toPromptImage(PromptImageMode.ResizeToFit, wrongDimensions) }
    }

    test("transformer failures propagate unchanged from bytes") {
        val bytes = pngBytes(4096, 2048)
        val failure = IllegalStateException("codec failure")
        val failing = PromptImageTransformer { throw failure }
        assertSame(failure, assertFailsWith<IllegalStateException> {
            bytes.toPromptImage(PromptImageMode.ResizeToFit, failing)
        })
    }
}
