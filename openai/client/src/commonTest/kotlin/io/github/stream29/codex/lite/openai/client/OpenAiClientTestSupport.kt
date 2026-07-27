package io.github.stream29.codex.lite.openai.client

import io.github.stream29.codex.lite.cli.auth.CodexAuthStore
import io.github.stream29.codex.lite.cli.auth.InMemoryCodexAuthStore
import io.github.stream29.codex.lite.openai.OpenAiErrorResponse
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.OpenAiResult
import io.github.stream29.codex.lite.openai.OpenAiSubscriptionAuthState
import io.github.stream29.codex.lite.openai.codexclistorage.CodexAuthJson
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliStorage
import io.github.stream29.codex.lite.utils.osenvironment.environmentVariable
import io.github.stream29.codex.lite.utils.osenvironment.userHomeDirectory
import kotlinx.io.files.Path
import kotlin.test.fail

internal val ImageGenerationTestModel: OpenAiModelId = OpenAiModelId("gpt-image-2")

internal fun <T> OpenAiResult<T, OpenAiErrorResponse>.successOrFail(): T =
    when (this) {
        is OpenAiResult.Success -> value
        is OpenAiResult.Failure -> fail("OpenAI request failed: ${error.messageText ?: error}")
    }

internal suspend fun codexAuthStore(): CodexAuthStore =
    InMemoryCodexAuthStore(
        testCodexStorage().readAuthOrNull().toSubscriptionAuthStateOrThrow(),
    )

private fun CodexAuthJson?.toSubscriptionAuthStateOrThrow(): OpenAiSubscriptionAuthState {
    val tokens = this?.tokens ?: error("Codex CLI auth tokens are required.")
    return OpenAiSubscriptionAuthState(
        accessToken = tokens.accessToken,
        accountId = tokens.accountId?.takeIf(String::isNotBlank),
    )
}

internal fun testCodexDirectory(): Path {
    val explicitCodexHome = environmentVariable("CODEX_HOME")?.takeIf(String::isNotBlank)
    if (explicitCodexHome != null) {
        return Path(explicitCodexHome)
    }
    return userHomeDirectory()?.let { home -> Path(home, ".codex") }
        ?: throw IllegalStateException("CODEX_HOME or a readable user home directory must be set for real OpenAI client tests.")
}

internal suspend fun testCodexClientVersion(): String =
    testCodexStorage().readModelsCacheOrNull()?.clientVersion
        ?.takeIf { it.matches(Regex("""\d+\.\d+\.\d+""")) }
        ?: "0.1.0"

internal suspend fun testCodexModel(): OpenAiModelId =
    OpenAiModelId(cachedModels().let { models ->
        configModel()
            ?: models.firstOrNull { it.contains("codex", ignoreCase = true) }
            ?: models.firstOrNull()
    } ?: fail("Codex CLI models_cache.json must contain at least one model."))

private fun testCodexStorage(): CodexCliStorage =
    CodexCliStorage(testCodexDirectory())

private suspend fun configModel(): String? {
    return testCodexStorage().readConfigTomlOrNull()?.model
}

private suspend fun cachedModels(): List<String> =
    testCodexStorage()
        .readModelsCacheOrNull()
        ?.models
        .orEmpty()
        .map { it.slug.value }

internal val png64x32DataUrl: String
    get() = "data:image/png;base64,$png64x32Base64"

private const val png64x32Base64: String =
    "iVBORw0KGgoAAAANSUhEUgAAAEAAAAAgCAYAAACinX6EAAAAgklEQVR4Xu3QoRHDABADQeNgY+MUkf6rcC82XxKskcASgZ/5Oz7n9TQ7HNosgEObBXBoswAObf4GuH/faP6jBXCQB9P4jxbAQR5M4z9aAAd5MI3/aAEc5ME0/qMFcJAH0/iPFsBBHkzjP1oAB3kwjf9oARzaLIBDmwVwaLMADm3qA7y8LuS12WzThwAAAABJRU5ErkJggg=="
