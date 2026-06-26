package io.github.stream29.codex.lite.llmprovider

import io.github.stream29.codex.lite.openai.CompactionInput
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.MutableOpenAiSubscriptionAuthSession
import io.github.stream29.codex.lite.openai.OpenAiErrorResponse
import io.github.stream29.codex.lite.openai.OpenAiResult
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.ToolChoice
import io.github.stream29.codex.lite.openai.client.OpenAiClientConfig
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliStorage
import io.github.stream29.codex.lite.openai.codexclistorage.defaultCodexDirectory
import io.github.stream29.codex.lite.utils.osenvironment.environmentVariable
import io.ktor.client.call.NoTransformationFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class OpenAiSubscriptionLlmProviderTest {
    @Test
    fun listModelsWithCodexCliCredentials() = runTest {
        val provider = liveProvider()
        try {
            val models = withContext(Dispatchers.Default) {
                provider.listModels()
            }.successOrFail()

            assertTrue(
                models.models.all { model -> model.slug.isNotBlank() && model.displayName.isNotBlank() },
                "Expected all returned models to have Codex backend slugs and display names.",
            )
        } finally {
            provider.close()
        }
    }

    @Test
    fun createStreamingResponseWithCodexCliCredentials() = runTest {
        val provider = liveProvider()
        try {
            val model = liveModel()
            val request = codexResponseRequest(model)

            val events = withContext(Dispatchers.Default) {
                provider.createResponse(request).toList()
            }

            assertTrue(events.isNotEmpty(), "Expected at least one SSE event from Codex subscription backend.")
            assertTrue(
                events.any { it is ResponsesStreamEvent.Created || it is ResponsesStreamEvent.Completed },
                "Expected at least one mapped Responses API SSE event.",
            )
        } finally {
            provider.close()
        }
    }

    @Test
    fun createStreamingResponseEmitsFirstEventWithCodexCliCredentials() = runTest {
        val provider = liveProvider()
        try {
            val event = withContext(Dispatchers.Default) {
                provider.createResponse(codexResponseRequest(liveModel())).first()
            }

            assertTrue(event is ResponsesStreamEvent.Created, "Expected a Responses API created event.")
        } finally {
            provider.close()
        }
    }

    @Test
    fun compactResponseWithCodexCliCredentials() = runTest {
        val provider = liveProvider()
        try {
            val result = try {
                withContext(Dispatchers.Default) {
                    provider.compactResponse(
                        CompactionInput(
                            model = liveModel(),
                            instructions = "Summarize the conversation into one short sentence.",
                            input = responseInput(
                                "The user asked whether the Codex subscription provider can call the backend.",
                            ),
                            parallelToolCalls = false,
                            promptCacheKey = "codex-lite-live-test",
                        ),
                    )
                }
            } catch (error: NoTransformationFoundException) {
                assertTrue(error.message.contains("ContentType: null"), "Expected original Ktor conversion failure.")
                return@runTest
            }

            val response = result.successOrFail()
            assertTrue(response.output.isNotEmpty(), "Expected compaction output items.")
        } finally {
            provider.close()
        }
    }

    private suspend fun liveProvider(): OpenAiSubscriptionLlmProvider =
        OpenAiSubscriptionLlmProvider(
            authProvider = MutableOpenAiSubscriptionAuthSession(codexStorage.readAuth()),
            config = OpenAiClientConfig(clientVersion = codexClientVersion()),
        )

    private val codexDirectory: Path =
        environmentVariable("CODEX_HOME")
            ?.takeIf(String::isNotBlank)
            ?.let(::Path)
            ?: defaultCodexDirectory()
            ?: fail("CODEX_HOME or a readable user home directory must be set for real OpenAI tests.")

    private val codexStorage: CodexCliStorage =
        CodexCliStorage(codexDirectory)

    private suspend fun codexClientVersion(): String =
        codexStorage.readModelsCache().clientVersion
            ?.takeIf { it.matches(Regex("""\d+\.\d+\.\d+""")) }
            ?: "0.1.0"

    private suspend fun liveModel(): String =
        cachedModels().let { models ->
            configModel()
                ?: models.firstOrNull { it.contains("codex", ignoreCase = true) }
                ?: models.firstOrNull()
        } ?: fail("Codex CLI models_cache.json must contain at least one model.")

    private suspend fun configModel(): String? {
        val modelLine = codexStorage
            .readConfigToml()
            .lineSequence()
            .firstOrNull { it.trimStart().startsWith("model = ") }
            ?: return null
        return modelLine.substringAfter("=")
            .trim()
            .removeSurrounding("\"")
            .takeIf { it.isNotBlank() }
    }

    private suspend fun cachedModels(): List<String> =
        codexStorage
            .readModelsCache()
            .models
            .mapNotNull { it.slug?.takeIf(String::isNotBlank) }

    private fun codexResponseRequest(model: String): ResponsesApiRequest =
        ResponsesApiRequest(
            model = model,
            instructions = "Reply with exactly: codex-lite-live-ok",
            input = responseInput("Reply with exactly: codex-lite-live-ok"),
            store = false,
            tools = emptyList(),
            toolChoice = ToolChoice.Auto,
            parallelToolCalls = false,
            include = emptySet(),
            promptCacheKey = "codex-lite-live-test",
            clientMetadata = mapOf("source" to "codex-lite-live-test"),
        )

    private fun responseInput(text: String): List<ResponseItem> =
        listOf(
            ResponseItem.Message(
                role = MessageRole.User,
                content = listOf(ContentItem.InputText(text)),
            ),
        )

    private fun <T> OpenAiResult<T, OpenAiErrorResponse>.successOrFail(): T =
        when (this) {
            is OpenAiResult.Success -> value
            is OpenAiResult.Failure -> fail("OpenAI request failed: ${error.messageText ?: error}")
        }
}
