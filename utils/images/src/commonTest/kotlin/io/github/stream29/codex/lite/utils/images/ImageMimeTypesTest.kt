package io.github.stream29.codex.lite.utils.images

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageMimeTypesTest {
    @Test
    fun detectImageMimeTypeRecognizesSupportedSignatures() {
        assertEquals(
            ImageMimeType.Png,
            byteArrayOf(-0x77, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).detectImageMimeType(),
        )
        assertEquals(ImageMimeType.Jpeg, byteArrayOf(-0x01, -0x28, -0x01).detectImageMimeType())
        assertEquals(ImageMimeType.Gif, "GIF89a".encodeToByteArray().detectImageMimeType())
    }

    @Test
    fun detectImageMimeTypeReturnsNullForUnknownBytes() {
        assertNull("not an image".encodeToByteArray().detectImageMimeType())
        assertNull("RIFFxxxxWEBP".encodeToByteArray().detectImageMimeType())
        assertFailsWith<UnsupportedImageFormatException> {
            "not an image".encodeToByteArray().requireImageMimeType()
        }
    }

    @Test
    fun canPreserveSourceBytesMatchesCodexSupportedFormats() {
        assertTrue(ImageMimeType.Png.canPreserveSourceBytes)
        assertTrue(ImageMimeType.Jpeg.canPreserveSourceBytes)
        assertFalse(ImageMimeType.Gif.canPreserveSourceBytes)
    }
}
