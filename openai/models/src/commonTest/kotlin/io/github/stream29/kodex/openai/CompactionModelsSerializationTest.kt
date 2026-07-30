package io.github.stream29.kodex.openai

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals

val compactionModelsSerializationTest by testSuite {
    test("agent settings serialize cwd as a string") {
        val settings = KodexAgentSettings(
            model = OpenAiModelId("test-model"),
            cwd = Path("/workspace/project"),
        )

        val encoded = OpenAiJsonCodec
            .encodeToJsonElement(KodexAgentSettings.serializer(), settings)
            .jsonObject

        assertEquals(JsonPrimitive("/workspace/project"), encoded["cwd"])
        assertEquals(
            settings,
            OpenAiJsonCodec.decodeFromJsonElement(KodexAgentSettings.serializer(), encoded),
        )
    }

    test("agent settings use the compatibility cwd fallback for legacy data") {
        val decoded = OpenAiJsonCodec.decodeFromJsonElement(
            KodexAgentSettings.serializer(),
            JsonObject(mapOf("model" to JsonPrimitive("test-model"))),
        )

        assertEquals(Path("."), decoded.cwd)
    }
}
