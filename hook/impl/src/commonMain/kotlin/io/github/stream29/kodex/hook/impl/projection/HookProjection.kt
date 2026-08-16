package io.github.stream29.kodex.hook.impl.projection

import io.github.stream29.kodex.hook.contract.HookTurnContext
import io.github.stream29.kodex.hook.contract.HookType
import io.github.stream29.kodex.hook.impl.ExecutableHook
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** Kodex-native command input shared by every Hook type. */
@Serializable
internal data class HookCommandInput(
    val name: String,
    val type: HookType,
    @SerialName("session_id") val sessionId: String,
    @SerialName("turn_id") val turnId: String,
    val cwd: String,
    val model: String,
    val payload: JsonElement,
)

internal inline fun <reified Payload> encodeHookInput(
    hook: ExecutableHook,
    type: HookType,
    context: HookTurnContext,
    payload: Payload,
): String {
    val session = context.session
    return HookJson.encodeToString(
        HookCommandInput(
            name = hook.name,
            type = type,
            sessionId = session.sessionId,
            turnId = context.turnId,
            cwd = session.cwd.toString(),
            model = session.model,
            payload = HookJson.encodeToJsonElement(payload),
        ),
    )
}

internal fun String.trimmedNonEmpty(): String? = trim().takeIf(String::isNotEmpty)

internal inline fun <reified Output> decodeHookOutputOrNull(text: String): Output? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    return try {
        val element = HookJson.parseToJsonElement(trimmed)
        if (element !is JsonObject) return null
        HookJson.decodeFromJsonElement<Output>(element)
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
