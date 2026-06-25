package io.github.stream29.codex.lite.utils.images

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptImageProcessingTest {
    @Test
    fun planPromptImagePreservesSupportedSourceBytesWhenNoResizeIsNeeded() {
        val source = ImageInfo(ImageMimeType.Png, ImageDimensions(64, 32))
        val plan = source.planPromptImage(PromptImageMode.ResizeToFit)

        assertEquals(ImageDimensions(64, 32), plan.outputDimensions)
        assertEquals(ImageMimeType.Png, plan.outputMimeType)
        assertTrue(plan.preservesSourceBytes)
        assertFalse(plan.requiresTransformation)
    }

    @Test
    fun planPromptImageRequestsResizeForLargeImages() {
        val source = ImageInfo(ImageMimeType.Png, ImageDimensions(4096, 2048))
        val plan = source.planPromptImage(PromptImageMode.ResizeToFit)

        assertEquals(ImageDimensions(2048, 1024), plan.outputDimensions)
        assertEquals(ImageMimeType.Png, plan.outputMimeType)
        assertFalse(plan.preservesSourceBytes)
        assertTrue(plan.requiresTransformation)
    }

    @Test
    fun planPromptImagePreservesSupportedOutputFormatWhenResizeIsNeeded() {
        val source = ImageInfo(ImageMimeType.Jpeg, ImageDimensions(4096, 2048))
        val plan = source.planPromptImage(PromptImageMode.ResizeToFit)

        assertEquals(ImageDimensions(2048, 1024), plan.outputDimensions)
        assertEquals(ImageMimeType.Jpeg, plan.outputMimeType)
        assertTrue(plan.requiresTransformation)
    }

    @Test
    fun planPromptImageConvertsGifToPng() {
        val source = ImageInfo(ImageMimeType.Gif, ImageDimensions(64, 32))
        val plan = source.planPromptImage(PromptImageMode.ResizeToFit)

        assertEquals(ImageDimensions(64, 32), plan.outputDimensions)
        assertEquals(ImageMimeType.Png, plan.outputMimeType)
        assertTrue(plan.requiresTransformation)
    }

    @Test
    fun toPromptImageReturnsEncodedImageWhenBytesCanBePreserved() {
        val bytes = pngBytes(64, 32)
        val image = bytes.toPromptImage(PromptImageMode.Original)

        assertEquals(ImageMimeType.Png, image.mimeType)
        assertEquals(ImageDimensions(64, 32), image.dimensions)
        assertContentEquals(bytes, image.bytes)
        assertTrue(image.toDataUrl().startsWith("data:image/png;base64,"))
    }

    @Test
    fun toPromptImageRequiresTransformerForResizeOrTranscode() {
        assertFailsWith<ImageTransformRequiredException> {
            pngBytes(4096, 2048).toPromptImage(PromptImageMode.ResizeToFit)
        }
        assertFailsWith<ImageTransformRequiredException> {
            gifBytes(64, 32).toPromptImage(PromptImageMode.ResizeToFit)
        }
    }

    @Test
    fun toPromptImageProcessesDataUrls() {
        val bytes = pngBytes(64, 32)
        val image = bytes.toDataUrl(ImageMimeType.Png).toPromptImage(PromptImageMode.Original)

        assertEquals(ImageMimeType.Png, image.mimeType)
        assertEquals(ImageDimensions(64, 32), image.dimensions)
        assertContentEquals(bytes, image.bytes)
    }
}
