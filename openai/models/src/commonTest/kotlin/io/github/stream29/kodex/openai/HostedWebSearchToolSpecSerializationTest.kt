package io.github.stream29.kodex.openai

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.encodeToString
import kotlin.test.assertEquals

val hostedWebSearchToolSpecSerializationTest by testSuite {
    test("hosted web search serializes indexed live access") {
        val tool = ToolSpec.WebSearch(
            externalWebAccess = true,
            indexedWebAccess = true,
            filters = ResponsesApiWebSearchFilters(allowedDomains = listOf("example.com")),
            userLocation = ResponsesApiWebSearchUserLocation(country = "US"),
            searchContextSize = WebSearchContextSize.Low,
            searchContentTypes = listOf("text", "image"),
        )

        val encoded = OpenAiJsonCodec.parseToJsonElement(
            OpenAiJsonCodec.encodeToString(ToolSpec.serializer(), tool),
        )

        assertEquals(
            OpenAiJsonCodec.parseToJsonElement(
                """
                    {
                      "type": "web_search",
                      "external_web_access": true,
                      "indexed_web_access": true,
                      "filters": {"allowed_domains": ["example.com"]},
                      "user_location": {"type": "approximate", "country": "US"},
                      "search_context_size": "low",
                      "search_content_types": ["text", "image"]
                    }
                """.trimIndent(),
            ),
            encoded,
        )
    }
}
