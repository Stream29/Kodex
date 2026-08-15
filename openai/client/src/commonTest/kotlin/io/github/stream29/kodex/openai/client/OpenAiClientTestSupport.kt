package io.github.stream29.kodex.openai.client

import io.github.stream29.kodex.openai.OpenAiErrorResponse
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiResult
import io.github.stream29.kodex.openai.OpenAiSubscriptionAuthState
import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import io.github.stream29.kodex.openai.client.test.InMemoryOpenAiAuthStore
import io.github.stream29.kodex.openai.codexclistorage.CodexAuthJson
import io.github.stream29.kodex.openai.codexclistorage.CodexCliStorage
import io.github.stream29.kodex.utils.osenvironment.environmentVariable
import io.github.stream29.kodex.utils.osenvironment.userHomeDirectory
import kotlinx.io.files.Path
import kotlin.test.fail

internal val ImageGenerationTestModel: OpenAiModelId = OpenAiModelId("gpt-image-2")
internal val ResponsesTestModel: OpenAiModelId = OpenAiModelId("gpt-5.6-sol")

internal fun <T> OpenAiResult<T, OpenAiErrorResponse>.successOrFail(): T =
    when (this) {
        is OpenAiResult.Success -> value
        is OpenAiResult.Failure -> fail("OpenAI request failed: ${error.messageText ?: error}")
    }

internal suspend fun kodexAuthStore(): OpenAiAuthStore =
    InMemoryOpenAiAuthStore(
        testAuthStorage().readAuthOrNull().toSubscriptionAuthStateOrThrow(),
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

private fun testAuthStorage(): CodexCliStorage =
    CodexCliStorage(testCodexDirectory())

internal val png64x32DataUrl: String
    get() = "data:image/png;base64,$png64x32Base64"

private const val png64x32Base64: String =
    "iVBORw0KGgoAAAANSUhEUgAAAEAAAAAgCAYAAACinX6EAAAAgklEQVR4Xu3QoRHDABADQeNgY+MUkf6rcC82XxKskcASgZ/5Oz7n9TQ7HNosgEObBXBoswAObf4GuH/faP6jBXCQB9P4jxbAQR5M4z9aAAd5MI3/aAEc5ME0/qMFcJAH0/iPFsBBHkzjP1oAB3kwjf9oARzaLIBDmwVwaLMADm3qA7y8LuS12WzThwAAAABJRU5ErkJggg=="
