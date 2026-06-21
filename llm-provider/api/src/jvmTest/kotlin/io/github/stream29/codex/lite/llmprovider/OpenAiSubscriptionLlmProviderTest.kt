package io.github.stream29.codex.lite.llmprovider

import io.github.stream29.codex.lite.codexcompatibility.readCodexAuth
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class OpenAiSubscriptionLlmProviderTest {
    @Test
    fun listModelsWithCodexCliCredentials() = runTest {
        val client = OpenAiSubscriptionLlmProviderHttpClient()
        try {
            val provider = liveProvider(client)

            val models = provider.listModels()

            assertTrue(
                models.models.all { model -> !model.slug.isNullOrBlank() || !model.id.isNullOrBlank() },
                "Expected all returned models to have ids or slugs.",
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun createResponseWithCodexCliCredentials() = runTest {
        val client = OpenAiSubscriptionLlmProviderHttpClient()
        try {
            val provider = liveProvider(client)
            val model = liveModel()
            val request = codexResponseRequest(model)

            val response = provider.createResponse(request)

            assertTrue(response.id?.isNotBlank() == true, "Expected a response id.")
        } finally {
            client.close()
        }
    }

    @Test
    fun streamResponseWithCodexCliCredentials() = runTest {
        val client = OpenAiSubscriptionLlmProviderHttpClient()
        try {
            val provider = liveProvider(client)
            val model = liveModel()
            val request = codexResponseRequest(model)

            val events = provider.streamResponse(request).toList()

            assertTrue(events.isNotEmpty(), "Expected at least one SSE event from Codex subscription backend.")
            assertTrue(
                events.any { it is LlmResponseStreamEvent.Created || it is LlmResponseStreamEvent.Completed },
                "Expected at least one mapped Responses API SSE event.",
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun streamResponseEmitsFirstEventWithCodexCliCredentials() = runTest {
        val client = OpenAiSubscriptionLlmProviderHttpClient()
        try {
            val provider = liveProvider(client)
            val event = provider.streamResponse(codexResponseRequest(liveModel())).first()

            assertTrue(event is LlmResponseStreamEvent.Created, "Expected a Responses API created event.")
        } finally {
            client.close()
        }
    }

    @Test
    fun compactResponseWithCodexCliCredentials() = runTest {
        val client = OpenAiSubscriptionLlmProviderHttpClient()
        try {
            val provider = liveProvider(client)
            val response = provider.compactResponse(
                LlmCompactionRequest(
                    model = liveModel(),
                    instructions = "Summarize the conversation into one short sentence.",
                    input = responseInput("The user asked whether the Codex subscription provider can call the backend."),
                    parallelToolCalls = false,
                    promptCacheKey = "codex-lite-live-test",
                ),
            )

            assertTrue(response.output.isNotEmpty(), "Expected compaction output items.")
        } finally {
            client.close()
        }
    }

    private fun liveProvider(client: HttpClient): OpenAiSubscriptionLlmProvider =
        OpenAiSubscriptionLlmProvider(
            authProvider = { readCodexAuth(codexDirectory) },
            httpClient = client,
            config = OpenAiSubscriptionLlmProviderConfig(clientVersion = codexClientVersion()),
        )

    private fun readCodexModelsCacheJson(): JsonObject =
        readJsonObject(codexPath("models_cache.json"), "Codex CLI models_cache.json")

    private fun readCodexConfigToml(): String =
        readText(codexPath("config.toml"), "Codex CLI config.toml")

    private fun readJsonObject(path: Path, label: String): JsonObject {
        val text = readText(path, label)
        return Json.parseToJsonElement(text) as? JsonObject
            ?: fail("$label must be a JSON object.")
    }

    private fun readText(path: Path, label: String): String {
        if (!SystemFileSystem.exists(path)) {
            fail("$label was not found at $path.")
        }
        val source = SystemFileSystem.source(path).buffered()
        val text = try {
            source.readString()
        } finally {
            source.close()
        }
        if (text.isBlank()) {
            fail("$label must not be empty.")
        }
        return text
    }

    private val codexDirectory: Path = Path("/home/stream/.codex")

    private fun codexPath(fileName: String): Path = Path(codexDirectory, fileName)

    private fun codexClientVersion(): String =
        readCodexModelsCacheJson().string("client_version")
            ?.takeIf { it.matches(Regex("""\d+\.\d+\.\d+""")) }
            ?: "0.1.0"

    private fun liveModel(): String =
        configModel()
            ?: cachedModels().firstOrNull { it.contains("codex", ignoreCase = true) }
            ?: cachedModels().firstOrNull()
            ?: fail("Codex CLI models_cache.json must contain at least one model.")

    private fun configModel(): String? {
        val modelLine = readCodexConfigToml()
            .lineSequence()
            .firstOrNull { it.trimStart().startsWith("model = ") }
            ?: return null
        return modelLine.substringAfter("=")
            .trim()
            .removeSurrounding("\"")
            .takeIf { it.isNotBlank() }
    }

    private fun cachedModels(): List<String> =
        (readCodexModelsCacheJson()["models"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.string("slug")?.takeIf(String::isNotBlank) }
            .orEmpty()

    private fun codexResponseRequest(model: String): LlmResponseRequest =
        LlmResponseRequest(
            model = model,
            instructions = "Reply with exactly: codex-lite-live-ok",
            input = responseInput("Reply with exactly: codex-lite-live-ok"),
            store = false,
            tools = emptyList(),
            toolChoice = LlmToolChoice.Auto,
            parallelToolCalls = false,
            include = emptySet(),
            promptCacheKey = "codex-lite-live-test",
            clientMetadata = mapOf("source" to "codex-lite-live-test"),
        )

    private fun responseInput(text: String): List<LlmResponseItem> =
        listOf(
            LlmResponseItem.Message(
                role = LlmMessageRole.User,
                content = listOf(LlmContentItem.InputText(text)),
            ),
        )

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull
}
