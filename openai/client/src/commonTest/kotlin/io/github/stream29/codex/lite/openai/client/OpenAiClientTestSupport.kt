package io.github.stream29.codex.lite.openai.client

import io.github.stream29.codex.lite.openai.OpenAiErrorResponse
import io.github.stream29.codex.lite.openai.OpenAiResult
import io.github.stream29.codex.lite.openai.OpenAiSubscriptionAuthProvider
import io.github.stream29.codex.lite.openai.codexclistorage.readCodexAuth
import io.github.stream29.codex.lite.utils.hosttest.environmentVariable
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.test.fail

internal const val ImageGenerationTestModel: String = "gpt-image-2"

internal fun <T> OpenAiResult<T, OpenAiErrorResponse>.successOrFail(): T =
    when (this) {
        is OpenAiResult.Success -> value
        is OpenAiResult.Failure -> fail("OpenAI request failed: ${error.messageText ?: error}")
    }

internal fun codexAuthProvider(): OpenAiSubscriptionAuthProvider =
    OpenAiSubscriptionAuthProvider {
        readCodexAuth(testCodexDirectory())
    }

internal fun testCodexDirectory(): Path {
    val explicitCodexHome = environmentVariable("CODEX_HOME")?.takeIf(String::isNotBlank)
    if (explicitCodexHome != null) {
        return Path(explicitCodexHome)
    }

    val userHome = environmentVariable("HOME")?.takeIf(String::isNotBlank)
        ?: environmentVariable("USERPROFILE")?.takeIf(String::isNotBlank)
        ?: throw IllegalStateException("CODEX_HOME, HOME, or USERPROFILE must be set for real OpenAI client tests.")
    return Path(userHome, ".codex")
}

internal fun testCodexClientVersion(): String =
    readCodexModelsCacheJson().string("client_version")
        ?.takeIf { it.matches(Regex("""\d+\.\d+\.\d+""")) }
        ?: "0.1.0"

internal fun testCodexModel(): String =
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

private fun readCodexModelsCacheJson(): JsonObject =
    readJsonObject(Path(testCodexDirectory(), "models_cache.json"), "Codex CLI models_cache.json")

private fun readCodexConfigToml(): String =
    readText(Path(testCodexDirectory(), "config.toml"), "Codex CLI config.toml")

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

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

internal val png64x32DataUrl: String
    get() = "data:image/png;base64,$png64x32Base64"

private const val png64x32Base64: String =
    "iVBORw0KGgoAAAANSUhEUgAAAEAAAAAgCAYAAACinX6EAAAAgklEQVR4Xu3QoRHDABADQeNgY+MUkf6rcC82XxKskcASgZ/5Oz7n9TQ7HNosgEObBXBoswAObf4GuH/faP6jBXCQB9P4jxbAQR5M4z9aAAd5MI3/aAEc5ME0/qMFcJAH0/iPFsBBHkzjP1oAB3kwjf9oARzaLIBDmwVwaLMADm3qA7y8LuS12WzThwAAAABJRU5ErkJggg=="
