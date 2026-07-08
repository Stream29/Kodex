package io.github.stream29.codex.lite.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable(with = FunctionCallOutputPayloadSerializer::class)
public data class FunctionCallOutputPayload(
    public val body: FunctionCallOutputBody = FunctionCallOutputBody.Text(""),
    public val success: Boolean? = null,
) {
    public companion object {
        public fun fromText(text: String): FunctionCallOutputPayload =
            FunctionCallOutputPayload(FunctionCallOutputBody.Text(text))

        public fun fromContentItems(
            contentItems: List<FunctionCallOutputContentItem>,
        ): FunctionCallOutputPayload =
            FunctionCallOutputPayload(FunctionCallOutputBody.ContentItems(contentItems))
    }
}

public sealed interface FunctionCallOutputBody {
    public data class Text(public val text: String) : FunctionCallOutputBody
    public data class ContentItems(
        public val items: List<FunctionCallOutputContentItem>,
    ) : FunctionCallOutputBody
}

/**
 * Mirrors MCP `CallToolResult`.
 *
 * `toFunctionCallOutputPayload` follows Rust `CallToolResult::into_function_call_output_payload`.
 *
 * @property structuredContent Nullable because MCP results may omit structured
 * content; `null` means no structured content was provided.
 * @property isError Nullable because MCP results may omit the error flag;
 * `null` means the result did not explicitly report error status.
 * @property meta Nullable because MCP results may omit metadata; `null` means
 * no metadata was provided.
 */
@Serializable
public data class CallToolResult(
    public val content: List<JsonElement>,
    public val structuredContent: JsonElement? = null,
    public val isError: Boolean? = null,
    @SerialName("_meta")
    public val meta: JsonElement? = null,
) {
    /**
     * Converts MCP tool results into the Responses `function_call_output`
     * payload shape used by Rust before sending tool output back to OpenAI.
     */
    public fun toFunctionCallOutputPayload(json: Json): FunctionCallOutputPayload {
        val success = isError != true
        val structured = structuredContent
        if (structured != null && structured !is JsonNull) {
            return FunctionCallOutputPayload(
                body = FunctionCallOutputBody.Text(json.encodeToString(JsonElement.serializer(), structured)),
                success = success,
            )
        }

        val contentItems = content.toFunctionCallOutputContentItems(json)
        return FunctionCallOutputPayload(
            body = contentItems?.let(FunctionCallOutputBody::ContentItems)
                ?: FunctionCallOutputBody.Text(json.encodeToString(contentSerializer, content)),
            success = success,
        )
    }
}

@Serializable
public sealed interface FunctionCallOutputContentItem {
    @Serializable
    @SerialName("input_text")
    public data class InputText(public val text: String) : FunctionCallOutputContentItem

    /**
     * @property detail Nullable because image detail is optional in function-call
     * output content; `null` means default image detail should apply.
     */
    @Serializable
    @SerialName("input_image")
    public data class InputImage(
        @SerialName("image_url")
        public val imageUrl: String,
        public val detail: ImageDetail? = null,
    ) : FunctionCallOutputContentItem

    @Serializable
    @SerialName("encrypted_content")
    public data class EncryptedContent(
        @SerialName("encrypted_content")
        public val encryptedContent: String,
    ) : FunctionCallOutputContentItem
}

private val contentSerializer = ListSerializer(JsonElement.serializer())

/**
 * @return Nullable because only mixed content containing images needs content
 * item conversion; `null` means callers should fall back to serialized text.
 */
private fun List<JsonElement>.toFunctionCallOutputContentItems(
    json: Json,
): List<FunctionCallOutputContentItem>? {
    var sawImage = false
    val items = map { content ->
        val contentObject = content as? JsonObject
        when (contentObject?.string("type")) {
            "text" -> FunctionCallOutputContentItem.InputText(
                text = contentObject.string("text").orEmpty(),
            )

            "image" -> {
                sawImage = true
                val data = contentObject.string("data").orEmpty()
                val imageUrl = if (data.startsWith("data:")) {
                    data
                } else {
                    val mimeType = contentObject.string("mimeType")
                        ?: contentObject.string("mime_type")
                        ?: "application/octet-stream"
                    "data:$mimeType;base64,$data"
                }
                FunctionCallOutputContentItem.InputImage(
                    imageUrl = imageUrl,
                    detail = contentObject["_meta"]
                        ?.let { it as? JsonObject }
                        ?.string("codex/imageDetail")
                        ?.let(::imageDetailFromWireName)
                        ?: ImageDetail.High,
                )
            }

            else -> FunctionCallOutputContentItem.InputText(
                text = json.encodeToString(JsonElement.serializer(), content),
            )
        }
    }

    return items.takeIf { sawImage }
}

/**
 * @return Nullable because the JSON member may be absent, non-primitive, or
 * JSON null; `null` means no string value is available.
 */
private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.jsonPrimitive?.contentOrNull

/**
 * @return Nullable because the wire value may not be recognized; `null` means
 * no `ImageDetail` mapping exists.
 */
private fun imageDetailFromWireName(value: String): ImageDetail? =
    when (value) {
        "auto" -> ImageDetail.Auto
        "low" -> ImageDetail.Low
        "high" -> ImageDetail.High
        "original" -> ImageDetail.Original
        else -> null
    }

