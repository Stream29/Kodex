package io.github.stream29.codex.lite.tool.websearch

import io.github.stream29.codex.lite.llmprovider.LlmContentItem
import io.github.stream29.codex.lite.llmprovider.LlmMessageRole
import io.github.stream29.codex.lite.llmprovider.LlmResponseItem
import io.github.stream29.codex.lite.llmprovider.LlmTool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class WebSearchSerializationTest {
    private val json = WebSearchClient.defaultJson

    @Test
    fun searchRequestSerializesCodexWireShape() {
        val request = SearchRequest(
            id = "search-session",
            model = "gpt-test",
            input = SearchInput.Items(
                listOf(
                    LlmResponseItem.Message(
                        role = LlmMessageRole.User,
                        content = listOf(LlmContentItem.InputText("find this")),
                    ),
                ),
            ),
            commands = SearchCommands(
                searchQuery = listOf(
                    SearchQuery(
                        q = "OpenAI news",
                        recency = 7u,
                        domains = listOf("openai.com"),
                    ),
                ),
                open = listOf(OpenOperation(refId = "https://openai.com", lineno = 12u)),
            ),
            settings = SearchSettings(
                userLocation = ApproximateLocation(country = "US", city = "San Francisco"),
                searchContextSize = SearchContextSize.Low,
                filters = SearchFilters(
                    allowedDomains = listOf("openai.com"),
                    blockedDomains = listOf("example.com"),
                ),
                imageSettings = SearchImageSettings(maxResults = 4u, caption = true),
                allowedCallers = listOf(AllowedCaller.Direct),
                externalWebAccess = true,
            ),
            maxOutputTokens = 2500u,
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

    @Test
    fun runToolUsesWebNamespace() {
        val encoded = json.parseToJsonElement(json.encodeToString(LlmTool.serializer(), WebSearchTools.runTool()))
            .jsonObject

        assertEquals(JsonPrimitive("namespace"), encoded["type"])
        assertEquals(JsonPrimitive("web"), encoded["name"])
    }
}
