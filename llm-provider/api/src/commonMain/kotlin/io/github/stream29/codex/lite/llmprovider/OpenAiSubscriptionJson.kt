package io.github.stream29.codex.lite.llmprovider

import kotlinx.serialization.Serializable

/**
 * @property error Nullable because the backend may return a nested error
 * object or flat fields; `null` means no nested object was present.
 * @property detail Nullable because flat error payloads may omit detail; `null`
 * means no detail string was present.
 * @property message Nullable because flat error payloads may omit message;
 * `null` means no message string was present.
 * @property code Nullable because flat error payloads may omit code; `null`
 * means no code was present.
 * @property type Nullable because flat error payloads may omit type; `null`
 * means no type was present.
 */
@Serializable
internal data class OpenAiSubscriptionErrorBody(
    val error: OpenAiSubscriptionError? = null,
    val detail: String? = null,
    val message: String? = null,
    val code: String? = null,
    val type: String? = null,
) {
    fun errorLike(): OpenAiSubscriptionError =
        error ?: OpenAiSubscriptionError(message = message ?: detail, code = code, type = type)
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
internal data class OpenAiSubscriptionError(
    val message: String? = null,
    val code: String? = null,
    val type: String? = null,
)
