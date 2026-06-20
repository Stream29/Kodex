package io.github.stream29.codex.lite.llmprovider

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
public sealed interface LlmTool {
    @Serializable
    @SerialName("function")
    public data class Function(
        public val name: String,
        public val description: String,
        public val strict: Boolean = false,
        @SerialName("defer_loading")
        public val deferLoading: Boolean? = null,
        public val parameters: LlmJsonSchema,
    ) : LlmTool

    @Serializable
    @SerialName("namespace")
    public data class Namespace(
        public val name: String,
        public val description: String,
        public val tools: List<LlmNamespaceTool>,
    ) : LlmTool

    @Serializable
    @SerialName("tool_search")
    public data class ToolSearch(
        public val execution: String,
        public val description: String,
        public val parameters: LlmJsonSchema,
    ) : LlmTool

    @Serializable
    @SerialName("image_generation")
    public data class ImageGeneration(
        @SerialName("output_format")
        public val outputFormat: String,
    ) : LlmTool

    @Serializable
    @SerialName("web_search")
    public data class WebSearch(
        @SerialName("external_web_access")
        public val externalWebAccess: Boolean? = null,
        public val filters: LlmWebSearchFilters? = null,
        @SerialName("user_location")
        public val userLocation: LlmWebSearchUserLocation? = null,
        @SerialName("search_context_size")
        public val searchContextSize: LlmWebSearchContextSize? = null,
        @SerialName("search_content_types")
        public val searchContentTypes: List<String>? = null,
    ) : LlmTool

    @Serializable
    @SerialName("custom")
    public data class Custom(
        public val name: String,
        public val description: String,
        public val format: LlmCustomToolFormat,
    ) : LlmTool
}

@Serializable
public sealed interface LlmLoadableToolSpec {
    @Serializable
    @SerialName("function")
    public data class Function(
        public val name: String,
        public val description: String,
        public val strict: Boolean = false,
        @SerialName("defer_loading")
        public val deferLoading: Boolean? = null,
        public val parameters: LlmJsonSchema,
    ) : LlmLoadableToolSpec

    @Serializable
    @SerialName("namespace")
    public data class Namespace(
        public val name: String,
        public val description: String,
        public val tools: List<LlmLoadableNamespaceTool>,
    ) : LlmLoadableToolSpec
}

@Serializable
public sealed interface LlmLoadableNamespaceTool {
    @Serializable
    @SerialName("function")
    public data class Function(
        public val name: String,
        public val description: String,
        public val strict: Boolean = false,
        @SerialName("defer_loading")
        public val deferLoading: Boolean? = null,
        public val parameters: LlmJsonSchema,
    ) : LlmLoadableNamespaceTool
}

@Serializable
public sealed interface LlmNamespaceTool {
    @Serializable
    @SerialName("function")
    public data class Function(
        public val name: String,
        public val description: String,
        public val strict: Boolean = false,
        @SerialName("defer_loading")
        public val deferLoading: Boolean? = null,
        public val parameters: LlmJsonSchema,
    ) : LlmNamespaceTool
}

@Serializable
public data class LlmCustomToolFormat(
    public val type: String,
    public val syntax: String,
    public val definition: String,
)

@Serializable
public data class LlmWebSearchFilters(
    @SerialName("allowed_domains")
    public val allowedDomains: List<String>? = null,
)

@Serializable
public data class LlmWebSearchUserLocation(
    public val type: LlmWebSearchUserLocationType = LlmWebSearchUserLocationType.Approximate,
    public val country: String? = null,
    public val region: String? = null,
    public val city: String? = null,
    public val timezone: String? = null,
)

@Serializable
public enum class LlmWebSearchUserLocationType {
    @SerialName("approximate")
    Approximate,
}

@Serializable
public enum class LlmWebSearchContextSize {
    @SerialName("low")
    Low,

    @SerialName("medium")
    Medium,

    @SerialName("high")
    High,
}

@Serializable
public data class LlmJsonSchema(
    @SerialName("\$ref")
    public val schemaRef: String? = null,
    public val type: LlmJsonSchemaType? = null,
    public val description: String? = null,
    public val encrypted: Boolean? = null,
    @SerialName("enum")
    public val enumValues: List<JsonElement>? = null,
    public val items: LlmJsonSchema? = null,
    public val properties: Map<String, LlmJsonSchema>? = null,
    public val required: List<String>? = null,
    @SerialName("additionalProperties")
    public val additionalProperties: LlmJsonSchemaAdditionalProperties? = null,
    @SerialName("anyOf")
    public val anyOf: List<LlmJsonSchema>? = null,
    @SerialName("oneOf")
    public val oneOf: List<LlmJsonSchema>? = null,
    @SerialName("allOf")
    public val allOf: List<LlmJsonSchema>? = null,
    @SerialName("\$defs")
    public val defs: Map<String, LlmJsonSchema>? = null,
    public val definitions: Map<String, LlmJsonSchema>? = null,
) {
    public companion object {
        public fun boolean(description: String? = null): LlmJsonSchema =
            primitive(LlmJsonSchemaPrimitiveType.Boolean, description)

        public fun string(description: String? = null): LlmJsonSchema =
            primitive(LlmJsonSchemaPrimitiveType.String, description)

        public fun number(description: String? = null): LlmJsonSchema =
            primitive(LlmJsonSchemaPrimitiveType.Number, description)

        public fun integer(description: String? = null): LlmJsonSchema =
            primitive(LlmJsonSchemaPrimitiveType.Integer, description)

        public fun nullValue(description: String? = null): LlmJsonSchema =
            primitive(LlmJsonSchemaPrimitiveType.Null, description)

        public fun array(items: LlmJsonSchema, description: String? = null): LlmJsonSchema =
            LlmJsonSchema(
                type = LlmJsonSchemaType.Single(LlmJsonSchemaPrimitiveType.Array),
                description = description,
                items = items,
            )

        public fun objectValue(
            properties: Map<String, LlmJsonSchema>,
            required: List<String>? = null,
            additionalProperties: LlmJsonSchemaAdditionalProperties? = null,
        ): LlmJsonSchema =
            LlmJsonSchema(
                type = LlmJsonSchemaType.Single(LlmJsonSchemaPrimitiveType.Object),
                properties = properties,
                required = required,
                additionalProperties = additionalProperties,
            )

        private fun primitive(
            type: LlmJsonSchemaPrimitiveType,
            description: String? = null,
        ): LlmJsonSchema =
            LlmJsonSchema(
                type = LlmJsonSchemaType.Single(type),
                description = description,
            )
    }
}

@Serializable(with = LlmJsonSchemaTypeSerializer::class)
public sealed interface LlmJsonSchemaType {
    public data class Single(public val value: LlmJsonSchemaPrimitiveType) : LlmJsonSchemaType
    public data class Multiple(public val values: List<LlmJsonSchemaPrimitiveType>) : LlmJsonSchemaType
}

@Serializable
public enum class LlmJsonSchemaPrimitiveType {
    @SerialName("string")
    String,

    @SerialName("number")
    Number,

    @SerialName("boolean")
    Boolean,

    @SerialName("integer")
    Integer,

    @SerialName("object")
    Object,

    @SerialName("array")
    Array,

    @SerialName("null")
    Null,
}

@Serializable(with = LlmJsonSchemaAdditionalPropertiesSerializer::class)
public sealed interface LlmJsonSchemaAdditionalProperties {
    public data class Boolean(public val value: kotlin.Boolean) : LlmJsonSchemaAdditionalProperties
    public data class Schema(public val value: LlmJsonSchema) : LlmJsonSchemaAdditionalProperties
}

public object LlmJsonSchemaTypeSerializer : KSerializer<LlmJsonSchemaType> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: LlmJsonSchemaType) {
        require(encoder is JsonEncoder) {
            "LlmJsonSchemaType can only be encoded as JSON."
        }
        val element = when (value) {
            is LlmJsonSchemaType.Single -> encoder.json.encodeToJsonElement(value.value)
            is LlmJsonSchemaType.Multiple -> encoder.json.encodeToJsonElement(value.values)
        }
        encoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): LlmJsonSchemaType {
        require(decoder is JsonDecoder) {
            "LlmJsonSchemaType can only be decoded as JSON."
        }
        return when (val element = decoder.decodeJsonElement()) {
            is JsonArray -> LlmJsonSchemaType.Multiple(
                decoder.json.decodeFromJsonElement<List<LlmJsonSchemaPrimitiveType>>(element),
            )
            else -> LlmJsonSchemaType.Single(
                decoder.json.decodeFromJsonElement<LlmJsonSchemaPrimitiveType>(element),
            )
        }
    }
}

public object LlmJsonSchemaAdditionalPropertiesSerializer : KSerializer<LlmJsonSchemaAdditionalProperties> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: LlmJsonSchemaAdditionalProperties) {
        require(encoder is JsonEncoder) {
            "LlmJsonSchemaAdditionalProperties can only be encoded as JSON."
        }
        val element = when (value) {
            is LlmJsonSchemaAdditionalProperties.Boolean -> JsonPrimitive(value.value)
            is LlmJsonSchemaAdditionalProperties.Schema -> encoder.json.encodeToJsonElement(value.value)
        }
        encoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): LlmJsonSchemaAdditionalProperties {
        require(decoder is JsonDecoder) {
            "LlmJsonSchemaAdditionalProperties can only be decoded as JSON."
        }
        val element = decoder.decodeJsonElement()
        return (element as? JsonPrimitive)?.booleanOrNull
            ?.let(LlmJsonSchemaAdditionalProperties::Boolean)
            ?: LlmJsonSchemaAdditionalProperties.Schema(decoder.json.decodeFromJsonElement(element))
    }
}
