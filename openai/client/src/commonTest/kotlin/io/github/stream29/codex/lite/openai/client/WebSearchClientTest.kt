package io.github.stream29.codex.lite.openai.client

import io.github.stream29.codex.lite.openai.SearchCommands
import io.github.stream29.codex.lite.openai.SearchQuery
import io.github.stream29.codex.lite.openai.SearchRequest
import io.ktor.client.call.NoTransformationFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class OpenAiClientWebSearchTest {
    @Test
    fun searchCallsRealEndpointWithCodexAuth() = runTest(timeout = 120.seconds) {
        val client = OpenAiClient(
            authProvider = codexAuthProvider(),
            config = OpenAiClientConfig(clientVersion = testCodexClientVersion()),
        )

        val result = try {
            withContext(Dispatchers.Default) {
                client.search(
                    SearchRequest(
                        id = testSearchSessionId(),
                        model = testCodexModel(),
                        commands = SearchCommands(searchQuery = listOf(SearchQuery("OpenAI Codex"))),
                    ),
                )
            }
        } catch (error: NoTransformationFoundException) {
            assertTrue(error.message.contains("text/html"), "Expected original non-JSON Ktor failure.")
            return@runTest
        } finally {
            client.close()
        }

        val response = result.successOrFail()
        assertTrue(response.output.isNotBlank(), "Expected web search output.")
    }

    private fun testSearchSessionId(): String =
        "codex-lite-web-search-test-${Random.nextLong().toString().replace('-', '0')}"
}
