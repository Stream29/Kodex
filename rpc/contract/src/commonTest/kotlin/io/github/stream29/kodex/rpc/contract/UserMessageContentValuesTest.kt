package io.github.stream29.kodex.rpc.contract

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.ImageDetail
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

val userMessageContentValuesTest by testSuite {
    test("original content variants preserve text images order and duplicates") {
        val text = ContentItem.InputText("用户输入\n{\"quoted\":\"value\"}\\")
        val content: List<ContentItem> = listOf(
            text,
            ContentItem.InputImage("data:image/png;base64,AA=="),
            ContentItem.InputImage("https://example.invalid/image", ImageDetail.Original),
            ContentItem.OutputText("original value variant"),
            text,
        )
        assertEquals(content, Json.decodeFromString<List<ContentItem>>(Json.encodeToString(content)))
        assertEquals(
            listOf(ContentItem.InputImage("fixture")),
            Json.decodeFromString<List<ContentItem>>(
                """[{"type":"input_image","image_url":"fixture"}]""",
            ),
        )
    }

    test("wire values distinguish empty lists from invalid or unknown content") {
        // Value encoding is not backend admission: this does not assert that an empty message is accepted.
        assertEquals(emptyList(), Json.decodeFromString<List<ContentItem>>("[]"))
        for (invalid in listOf(
            "null",
            "[null]",
            """[{"type":"unknown_content"}]""",
            """[{"type":"input_text"}]""",
            """[{"type":"input_image"}]""",
        )) {
            assertFailsWith<SerializationException> {
                Json.decodeFromString<List<ContentItem>>(invalid)
            }
        }
    }
}
