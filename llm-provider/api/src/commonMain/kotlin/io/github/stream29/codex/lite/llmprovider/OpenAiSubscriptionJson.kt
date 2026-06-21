package io.github.stream29.codex.lite.llmprovider

import kotlinx.serialization.Serializable

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

@Serializable
internal data class OpenAiSubscriptionError(
    val message: String? = null,
    val code: String? = null,
    val type: String? = null,
)
