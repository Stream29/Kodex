package io.github.stream29.codex.lite.llmprovider

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.MutableOpenAiSubscriptionAuthSession
import io.github.stream29.codex.lite.openai.OpenAiErrorResponse
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.OpenAiResult
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.ToolChoice
import io.github.stream29.codex.lite.openai.client.OpenAiClientConfig
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliStorage
import io.github.stream29.codex.lite.openai.codexclistorage.defaultCodexDirectory
import io.github.stream29.codex.lite.utils.osenvironment.environmentVariable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.test.assertTrue
import kotlin.test.fail

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

private suspend fun liveModel(): OpenAiModelId =
    OpenAiModelId(cachedModels().let { models ->
        configModel()
            ?: models.firstOrNull { it.contains("codex", ignoreCase = true) }
            ?: models.firstOrNull()
    } ?: fail("Codex CLI models_cache.json must contain at least one model."))

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

private fun codexResponseRequest(model: OpenAiModelId): ResponsesApiRequest =
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

val openAiSubscriptionLlmProviderTest by testSuite {
    testFixture { liveProvider() } asParameterForEach {
        test("list models with codex cli credentials") { provider ->
            val models = withContext(Dispatchers.Default) {
                provider.listModels()
            }.successOrFail()

            assertTrue(
                models.models.all { model -> model.slug.value.isNotBlank() && model.displayName.isNotBlank() },
                "Expected all returned models to have Codex backend slugs and display names.",
            )
        }

        test("create streaming response with codex cli credentials") { provider ->
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
        }

        test("create streaming response emits first event with codex cli credentials") { provider ->
            val event = withContext(Dispatchers.Default) {
                provider.createResponse(codexResponseRequest(liveModel())).first()
            }

            assertTrue(event is ResponsesStreamEvent.Created, "Expected a Responses API created event.")
        }
    }
}
