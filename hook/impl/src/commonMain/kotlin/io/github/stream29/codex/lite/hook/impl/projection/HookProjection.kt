package io.github.stream29.codex.lite.hook.impl.projection

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
internal enum class HookEventNameWire {
    @SerialName("PreToolUse") PreToolUse,
    @SerialName("PermissionRequest") PermissionRequest,
    @SerialName("PostToolUse") PostToolUse,
    @SerialName("PreCompact") PreCompact,
    @SerialName("PostCompact") PostCompact,
    @SerialName("UserPromptSubmit") UserPromptSubmit,
    @SerialName("Stop") Stop,
}

@Serializable
internal enum class BlockDecisionWire {
    @SerialName("block") Block,
}

internal fun String.looksLikeJson(): Boolean =
    trimStart().firstOrNull() in setOf('{', '[')

internal fun String.trimmedNonEmpty(): String? = trim().takeIf(String::isNotEmpty)

internal inline fun <reified T> decodeHookOutputOrNull(text: String): T? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    return try {
        val element = HookJson.parseToJsonElement(trimmed)
        if (element !is JsonObject) return null
        HookJson.decodeFromJsonElement<T>(element)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal val HookJson: Json = Json {
    ignoreUnknownKeys = false
    explicitNulls = true
}
