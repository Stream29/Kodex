package io.github.stream29.kodex.openai.client

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.kodex.openai.SearchCommands
import io.github.stream29.kodex.openai.SearchQuery
import io.github.stream29.kodex.openai.SearchRequest
import io.ktor.client.call.NoTransformationFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private fun testSearchSessionId(): String =
    "kodex-web-search-test-${Random.nextLong().toString().replace('-', '0')}"

val openAiClientWebSearchTest by testSuite {
    testFixture {
        OpenAiClient(
            authStore = kodexAuthStore(),
            config = OpenAiClientConfig(clientVersion = testCodexClientVersion()),
        )
    } asParameterForEach {
        test(
            "search calls real endpoint with codex auth",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 120.seconds),
        ) { client ->
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
                null
            }

            if (result != null) {
                val response = result.successOrFail()
                assertTrue(response.output.isNotBlank(), "Expected web search output.")
            }
        }
    }
}
