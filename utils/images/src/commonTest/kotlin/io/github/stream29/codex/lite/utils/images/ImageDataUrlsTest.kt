package io.github.stream29.codex.lite.utils.images

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ImageDataUrlsTest {
    @Test
    fun toDataUrlWrapsBytesWithoutValidation() {
        assertEquals(
            "data:image/png;base64,AAEC",
            byteArrayOf(0, 1, 2).toDataUrl("image/png"),
        )
    }

    @Test
    fun decodePromptImageDataUrlBytesAcceptsCaseInsensitiveMarkers() {
        val dataUrl = byteArrayOf(10, 20, 30).toDataUrl(ImageMimeType.Png)
            .replaceFirst("data:", "DATA:")
            .replaceFirst(";base64,", ";BASE64,")

        assertContentEquals(
            byteArrayOf(10, 20, 30),
            dataUrl.decodePromptImageDataUrlBytes(),
        )
    }

    @Test
    fun decodePromptImageDataUrlBytesRejectsMalformedInputs() {
        for (dataUrl in listOf(
            "image/png;base64,AAAA",
            "data:image/png;base64",
            "data:image/png,AAAA",
            "data:image/png;base64,not base64",
        )) {
            assertFailsWith<InvalidImageDataUrlException> {
                dataUrl.decodePromptImageDataUrlBytes()
            }
        }
    }

    @Test
    fun decodePromptImageDataUrlBytesRejectsLargeRepresentations() {
        val error = assertFailsWith<ImageInputTooLargeException> {
            "data:image/png;base64,AAAA".decodePromptImageDataUrlBytes(maxInputBytes = 3)
        }

        assertEquals("base64 payload", error.representation)
        assertEquals(4, error.size)
        assertEquals(3, error.max)
    }
}
