package io.github.stream29.codex.lite.openai

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform

public typealias OpenAiResponseResult<Success> = OpenAiResult<Success, OpenAiErrorResponse>

@Serializable(with = OpenAiResultSerializer::class)
public sealed interface OpenAiResult<out Success, out Failure> {
    public data class Success<out Success>(
        public val value: Success,
    ) : OpenAiResult<Success, Nothing>

    public data class Failure<out Failure>(
        public val error: Failure,
    ) : OpenAiResult<Nothing, Failure>
}

/**
 * Returns the successful payload or raises the structured OpenAI error.
 */
public fun <Success> OpenAiResponseResult<Success>.getOrThrow(): Success =
    when (this) {
        is OpenAiResult.Success -> value
        is OpenAiResult.Failure -> throw OpenAiResponseResultException(error)
    }

public class OpenAiResultSerializer<Success, Failure>(
    private val successSerializer: KSerializer<Success>,
    private val failureSerializer: KSerializer<Failure>,
) : KSerializer<OpenAiResult<Success, Failure>> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: OpenAiResult<Success, Failure>) {
        require(encoder is JsonEncoder) {
            "OpenAiResult can only be encoded as JSON."
        }
        val element = when (value) {
            is OpenAiResult.Success -> encoder.json.encodeToJsonElement(successSerializer, value.value)
            is OpenAiResult.Failure -> encoder.json.encodeToJsonElement(failureSerializer, value.error)
        }
        encoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): OpenAiResult<Success, Failure> {
        require(decoder is JsonDecoder) {
            "OpenAiResult can only be decoded as JSON."
        }
        val element = decoder.decodeJsonElement()
        return if (element.looksLikeOpenAiError()) {
            OpenAiResult.Failure(decoder.json.decodeFromJsonElement(failureSerializer, element))
        } else {
            OpenAiResult.Success(decoder.json.decodeFromJsonElement(successSerializer, element))
        }
    }

    private fun JsonElement.looksLikeOpenAiError(): Boolean {
        val obj = this as? JsonObject ?: return false
        return "error" in obj ||
            "detail" in obj ||
            "message" in obj ||
            "code" in obj ||
            "type" in obj
    }
}

/**
 * @property error Nullable because some OpenAI-compatible errors use flat
 * fields; `null` means no nested error object was provided.
 * @property detail Nullable because flat error payloads may omit detail; `null`
 * means no detail string was provided.
 * @property message Nullable because flat error payloads may omit message;
 * `null` means no message string was provided.
 * @property code Nullable because flat error payloads may omit code; `null`
 * means no code was provided.
 * @property type Nullable because flat error payloads may omit type; `null`
 * means no type was provided.
 */
@Serializable
public data class OpenAiErrorResponse(
    public val error: OpenAiError? = null,
    public val detail: String? = null,
    public val message: String? = null,
    public val code: String? = null,
    public val type: String? = null,
) {
    /**
     * Nullable because error payloads may omit every message-like field; `null`
     * means no human-readable message was provided.
     */
    public val messageText: String?
        get() = error?.message ?: message ?: detail

    /**
     * Nullable because error payloads may omit every code-like field; `null`
     * means no machine-readable code was provided.
     */
    public val codeText: String?
        get() = error?.code ?: code

    /**
     * Nullable because error payloads may omit every type-like field; `null`
     * means no machine-readable type was provided.
     */
    public val typeText: String?
        get() = error?.type ?: type
}

/**
 * @property message Nullable because backend errors may omit message; `null`
 * means no message string was provided.
 * @property code Nullable because backend errors may omit code; `null` means no
 * code was provided.
 * @property type Nullable because backend errors may omit type; `null` means no
 * type was provided.
 */
@Serializable
public data class OpenAiError(
    public val message: String? = null,
    public val code: String? = null,
    public val type: String? = null,
)

/**
 * Raised when a non-streaming OpenAI endpoint returns a structured error
 * result.
 */
public class OpenAiResponseResultException(
    public val error: OpenAiErrorResponse,
) : IllegalStateException(
    error.messageText ?: "OpenAI returned an error response without a message.",
)

/**
 * Raised when a Responses stream emits `response.failed`.
 */
public class OpenAiResponseStreamFailureException(
    public val error: ResponseError?,
) : IllegalStateException(
    error?.message ?: "OpenAI response stream failed without structured error.",
)

/**
 * Emits every stream event and raises [OpenAiResponseStreamFailureException]
 * immediately after a `response.failed` event reaches the caller.
 */
public fun Flow<ResponsesStreamEvent>.throwIfFailure(): Flow<ResponsesStreamEvent> =
    transform { event ->
        emit(event)
        if (event is ResponsesStreamEvent.Failed) {
            throw OpenAiResponseStreamFailureException(event.response.error)
        }
    }
