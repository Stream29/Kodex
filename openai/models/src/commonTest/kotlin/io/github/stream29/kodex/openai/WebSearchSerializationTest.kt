package io.github.stream29.kodex.openai

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlin.test.assertEquals

private val json = OpenAiJsonCodec

val webSearchSerializationTest by testSuite {
    test("search request serializes request shape") {
        val request = SearchRequest(
            id = "search-session",
            model = OpenAiModelId("gpt-test"),
            commands = SearchCommands(
                searchQuery = listOf(
                    SearchQuery(
                        q = "OpenAI news",
                        recency = 7,
                        domains = listOf("openai.com"),
                    ),
                ),
                open = listOf(OpenOperation(refId = "https://openai.com", lineno = 12)),
            ),
            settings = SearchSettings(
                externalWebAccess = true,
            ),
            maxOutputTokens = 2500,
        )

        val encoded = json.parseToJsonElement(json.encodeToString(SearchRequest.serializer(), request))

        assertEquals(
            json.parseToJsonElement(
                """
                    {
                      "id": "search-session",
                      "model": "gpt-test",
                      "reasoning": {"effort": "medium"},
                      "commands": {
                        "search_query": [{
                          "q": "OpenAI news",
                          "recency": 7,
                          "domains": ["openai.com"]
                        }],
                        "open": [{"ref_id": "https://openai.com", "lineno": 12}]
                      },
                      "settings": {
                        "external_web_access": true
                      },
                      "max_output_tokens": 2500
                    }
                """.trimIndent(),
            ),
            encoded,
        )
    }
}
