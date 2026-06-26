@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package io.github.stream29.codex.lite.tool.imagegeneration

import io.github.stream29.codex.lite.auth.OpenAiSubscriptionAuthProvider
import io.github.stream29.codex.lite.codexcompatibility.readCodexAuth
import kotlinx.io.files.Path
import kotlin.io.encoding.Base64

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
        ?: throw IllegalStateException("CODEX_HOME, HOME, or USERPROFILE must be set for real OpenAI image tests.")
    return Path(userHome, ".codex")
}

internal expect fun environmentVariable(name: String): String?

internal val png64x32DataUrl: String
    get() = "data:image/png;base64,$png64x32Base64"

internal fun decodePng64x32(): ByteArray =
    Base64.Default.decode(png64x32Base64)

private const val png64x32Base64: String =
    "iVBORw0KGgoAAAANSUhEUgAAAEAAAAAgCAYAAACinX6EAAAAgklEQVR4Xu3QoRHDABADQeNgY+MUkf6rcC82XxKskcASgZ/5Oz7n9TQ7HNosgEObBXBoswAObf4GuH/faP6jBXCQB9P4jxbAQR5M4z9aAAd5MI3/aAEc5ME0/qMFcJAH0/iPFsBBHkzjP1oAB3kwjf9oARzaLIBDmwVwaLMADm3qA7y8LuS12WzThwAAAABJRU5ErkJggg=="
