package io.github.stream29.codex.lite.openai

import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class WebSearchSerializationTest {
    private val json = OpenAiJsonCodec

    @Test
    fun searchRequestSerializesRequestShape() {
        val request = SearchRequest(
            id = "search-session",
            model = OpenAiModelId("gpt-test"),
            input = SearchInput.Items(
                listOf(
                    ResponseItem.Message(
                        role = MessageRole.User,
                        content = listOf(ContentItem.InputText("find this")),
                    ),
                ),
            ),
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
                userLocation = ApproximateLocation(country = "US", city = "San Francisco"),
                searchContextSize = SearchContextSize.Low,
                filters = SearchFilters(
                    allowedDomains = listOf("openai.com"),
                    blockedDomains = listOf("example.com"),
                ),
                imageSettings = SearchImageSettings(maxResults = 4, caption = true),
                allowedCallers = listOf(AllowedCaller.Direct),
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
                      "input": [{
                        "type": "message",
                        "role": "user",
                        "content": [{"type": "input_text", "text": "find this"}]
                      }],
                      "commands": {
                        "search_query": [{
                          "q": "OpenAI news",
                          "recency": 7,
                          "domains": ["openai.com"]
                        }],
                        "open": [{"ref_id": "https://openai.com", "lineno": 12}]
                      },
                      "settings": {
                        "user_location": {
                          "type": "approximate",
                          "country": "US",
                          "city": "San Francisco"
                        },
                        "search_context_size": "low",
                        "filters": {
                          "allowed_domains": ["openai.com"],
                          "blocked_domains": ["example.com"]
                        },
                        "image_settings": {"max_results": 4, "caption": true},
                        "allowed_callers": ["direct"],
                        "external_web_access": true
                      },
                      "max_output_tokens": 2500
                    }
                """.trimIndent(),
            ),
            encoded,
        )
    }

    @Test
    fun searchInputCanBeText() {
        val encoded = json.parseToJsonElement(
            json.encodeToString(SearchInput.serializer(), SearchInput.Text("query context")),
        )

        assertEquals(JsonPrimitive("query context"), encoded)
    }
}
