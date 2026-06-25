package io.github.stream29.codex.lite.utils.images

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ImageInfoTest {
    @Test
    fun detectImageInfoReadsSupportedContainerDimensions() {
        assertEquals(
            ImageInfo(ImageMimeType.Png, ImageDimensions(64, 32)),
            pngBytes(64, 32).detectImageInfo(),
        )
        assertEquals(
            ImageInfo(ImageMimeType.Gif, ImageDimensions(64, 32)),
            gifBytes(64, 32).detectImageInfo(),
        )
        assertEquals(
            ImageInfo(ImageMimeType.Jpeg, ImageDimensions(64, 32)),
            jpegBytes(64, 32).detectImageInfo(),
        )
        assertEquals(
            ImageInfo(ImageMimeType.Webp, ImageDimensions(64, 32)),
            webpExtendedBytes(64, 32).detectImageInfo(),
        )
        assertEquals(
            ImageInfo(ImageMimeType.Webp, ImageDimensions(64, 32)),
            webpLossyBytes(64, 32).detectImageInfo(),
        )
        assertEquals(
            ImageInfo(ImageMimeType.Webp, ImageDimensions(64, 32)),
            webpLosslessBytes(64, 32).detectImageInfo(),
        )
    }

    @Test
    fun detectImageInfoReturnsNullForUnsupportedBytes() {
        assertNull("not an image".encodeToByteArray().detectImageInfo())
        assertFailsWith<UnsupportedImageFormatException> {
            "not an image".encodeToByteArray().requireImageInfo()
        }
    }

    @Test
    fun detectImageInfoRejectsMalformedSupportedContainers() {
        assertFailsWith<InvalidImageException> {
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
                .detectImageInfo()
        }
        assertFailsWith<InvalidImageException> {
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xda.toByte())
                .detectImageInfo()
        }
    }
}
