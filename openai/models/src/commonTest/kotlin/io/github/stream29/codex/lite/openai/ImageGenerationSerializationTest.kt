package io.github.stream29.codex.lite.openai

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ImageGenerationSerializationTest {
    private val json = OpenAiJson.default

    @Test
    fun generationRequestOmitsNullOptionals() {
        val element = json.parseToJsonElement(
            json.encodeToString(ImageGenerationRequest(prompt = "draw", model = "gpt-image-1")),
        ).jsonObject

        assertEquals(JsonPrimitive("draw"), element["prompt"])
        assertEquals(JsonPrimitive("gpt-image-1"), element["model"])
        assertFalse("background" in element)
        assertFalse("n" in element)
        assertFalse("quality" in element)
        assertFalse("size" in element)
    }

    @Test
    fun editRequestSerializesOpenAiWireNames() {
        val element = json.parseToJsonElement(
            json.encodeToString(
                ImageEditRequest(
                    images = listOf(ImageUrl("data:image/png;base64,AAAA")),
                    prompt = "edit",
                    model = "gpt-image-1",
                    background = ImageBackground.Transparent,
                    quality = ImageQuality.High,
                    size = "1024x1024",
                ),
            ),
        ).jsonObject

        assertEquals(JsonPrimitive("transparent"), element["background"])
        assertEquals(JsonPrimitive("high"), element["quality"])
        assertEquals(
            JsonPrimitive("data:image/png;base64,AAAA"),
            (element["images"] as? JsonArray)
                ?.first()
                ?.jsonObject
                ?.get("image_url"),
        )
    }

    @Test
    fun responseDeserializesBase64Payload() {
        val response = json.decodeFromString<ImageResponse>(
            """{"created":1,"data":[{"b64_json":"encoded"}],"background":"auto"}""",
        )

        assertEquals(ImageResponse(1, listOf(ImageData("encoded")), background = ImageBackground.Auto), response)
    }
}
