@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package io.github.stream29.codex.lite.tool.imagegeneration

import io.github.stream29.codex.lite.openai.MutableOpenAiSubscriptionAuthSession
import io.github.stream29.codex.lite.openai.OpenAiSubscriptionAuthSession
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliStorage
import io.github.stream29.codex.lite.openai.codexclistorage.defaultCodexDirectory
import io.github.stream29.codex.lite.utils.osenvironment.environmentVariable
import kotlinx.io.files.Path
import kotlin.io.encoding.Base64

internal suspend fun codexAuthProvider(): OpenAiSubscriptionAuthSession =
    MutableOpenAiSubscriptionAuthSession(CodexCliStorage(testCodexDirectory()).readAuth())

internal fun testCodexDirectory(): Path {
    val explicitCodexHome = environmentVariable("CODEX_HOME")?.takeIf(String::isNotBlank)
    if (explicitCodexHome != null) {
        return Path(explicitCodexHome)
    }

    return defaultCodexDirectory()
        ?: throw IllegalStateException("CODEX_HOME or a readable user home directory must be set for real OpenAI image tests.")
}

internal val png64x32DataUrl: String
    get() = "data:image/png;base64,$png64x32Base64"

internal fun decodePng64x32(): ByteArray =
    Base64.Default.decode(png64x32Base64)

private const val png64x32Base64: String =
    "iVBORw0KGgoAAAANSUhEUgAAAEAAAAAgCAYAAACinX6EAAAAgklEQVR4Xu3QoRHDABADQeNgY+MUkf6rcC82XxKskcASgZ/5Oz7n9TQ7HNosgEObBXBoswAObf4GuH/faP6jBXCQB9P4jxbAQR5M4z9aAAd5MI3/aAEc5ME0/qMFcJAH0/iPFsBBHkzjP1oAB3kwjf9oARzaLIBDmwVwaLMADm3qA7y8LuS12WzThwAAAABJRU5ErkJggg=="
