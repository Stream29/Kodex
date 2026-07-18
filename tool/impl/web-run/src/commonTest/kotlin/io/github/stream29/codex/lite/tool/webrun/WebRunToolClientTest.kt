package io.github.stream29.codex.lite.tool.webrun

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.openai.FunctionCallOutputBody
import io.github.stream29.codex.lite.openai.MutableOpenAiSubscriptionAuthSession
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.OpenAiResult
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.SearchCommands
import io.github.stream29.codex.lite.openai.SearchQuery
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliStorage
import io.github.stream29.codex.lite.openai.codexclistorage.defaultCodexDirectory
import io.github.stream29.codex.lite.openai.client.OpenAiClient
import io.github.stream29.codex.lite.openai.client.OpenAiClientConfig
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.codex.lite.utils.osenvironment.environmentVariable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.serialization.encodeToString
import kotlin.random.Random
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

private suspend fun realWebRunClient(): OpenAiClient {
    val storage = CodexCliStorage(testCodexDirectory())
    val clientVersion = storage.readModelsCache().clientVersion
        ?.takeIf { it.matches(Regex("""\d+\.\d+\.\d+""")) }
        ?: "0.1.0"
    return OpenAiClient(
        authProvider = MutableOpenAiSubscriptionAuthSession(storage.readAuth()),
        config = OpenAiClientConfig(clientVersion = clientVersion),
    )
}

private suspend fun testModel(): OpenAiModelId {
    val storage = CodexCliStorage(testCodexDirectory())
    val model = storage.readModelsCache()
        .models
        .map { it.slug.value }
        .firstOrNull { it.contains("codex", ignoreCase = true) }
        ?: storage.readModelsCache().models.firstOrNull()?.slug?.value
        ?: error("Codex CLI models_cache.json must contain at least one model.")
    return OpenAiModelId(model)
}

private fun testCodexDirectory(): Path {
    val explicitCodexHome = environmentVariable("CODEX_HOME")?.takeIf(String::isNotBlank)
    if (explicitCodexHome != null) return Path(explicitCodexHome)
    return defaultCodexDirectory()
        ?: error("CODEX_HOME or a readable user home directory must be set for real web.run tests.")
}

private fun testSessionId(): String =
    "codex-lite-web-run-test-${Random.nextLong().toString().replace('-', '0')}"

val webRunToolClientTest by testSuite {
    testFixture { realWebRunClient() } closeWith { close() } asParameterForEach {
        test(
            "run calls the real web search endpoint",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 120.seconds),
        ) { client ->
            val response = withContext(Dispatchers.Default) {
                WebRunToolClient(
                    client = client,
                    sessionId = testSessionId(),
                    model = testModel(),
                ).run(
                    SearchCommands(
                        searchQuery = listOf(SearchQuery("OpenAI Codex")),
                    ),
                )
            }
            val result = when (response) {
                is OpenAiResult.Success -> response.value
                is OpenAiResult.Failure -> fail(
                    "OpenAI web search failed: ${response.error.messageText ?: response.error}",
                )
            }

            assertTrue(result.output.isNotBlank(), "Expected web.run output.")
        }

        test(
            "tool handler forwards a real web search result",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 120.seconds),
        ) { client ->
            val commands = SearchCommands(searchQuery = listOf(SearchQuery("OpenAI Codex")))
            val tool = WebRunTools.createTool(
                WebRunToolClient(
                    client = client,
                    sessionId = testSessionId(),
                    model = testModel(),
                ),
            )
            val output = withContext(Dispatchers.Default) {
                tool.handle(
                    ResponseItem.FunctionCall(
                        name = WebRunToolName,
                        namespace = WebRunNamespace,
                        arguments = OpenAiJsonCodec.encodeToString(commands),
                        callId = "call_web_run",
                    ),
                )
            } as ResponseItem.FunctionCallOutput

            assertTrue(output.output.success == true)
            assertTrue((output.output.body as FunctionCallOutputBody.Text).text.isNotBlank())
        }
    }
}
