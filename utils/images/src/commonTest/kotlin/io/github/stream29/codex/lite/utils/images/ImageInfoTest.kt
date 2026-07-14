package io.github.stream29.codex.lite.utils.images

import de.infix.testBalloon.framework.core.testSuite

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull



val imageInfoTest by testSuite {
    test("detect image info reads supported container dimensions") {
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
    }

    test("detect image info returns null for unsupported bytes") {
        assertNull("not an image".encodeToByteArray().detectImageInfo())
        assertNull(riffWebpBytes().detectImageInfo())
        assertFailsWith<UnsupportedImageFormatException> {
            "not an image".encodeToByteArray().requireImageInfo()
        }
    }

    test("detect image info rejects malformed supported containers") {
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
