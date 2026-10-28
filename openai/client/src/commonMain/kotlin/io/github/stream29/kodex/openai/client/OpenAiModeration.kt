package io.github.stream29.kodex.openai.client

import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.OpenAiFailureDiagnostic
import io.github.stream29.kodex.openai.OpenAiFailureReason
import io.github.stream29.kodex.openai.OpenAiFailureTermination
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

public actual fun ResponsesStreamEvent.isExplicitModerationBlock(): Boolean = explicitModerationReason() != null

public actual fun ResponsesStreamEvent.explicitModerationReason(): OpenAiFailureReason? = when (this) {
    is ResponsesStreamEvent.Metadata -> {
        val moderation = metadata?.get("openai_chatgpt_moderation_metadata") as? JsonObject
        var cyberBlocked = false
        var blocked = false
        listOf("prompt", "response").forEach { side ->
            val categories = moderation?.get(side) as? JsonObject
            categories?.entries?.forEach { (name, category) ->
                val outputs = (category as? JsonObject)?.get("outputs") as? JsonArray
                val categoryBlocked = outputs?.any { output ->
                    val flag = (output as? JsonObject)?.get("is_blocked") as? JsonPrimitive
                    flag != null && !flag.isString && flag.booleanOrNull == true
                } == true
                if (categoryBlocked) blocked = true
                if (categoryBlocked && name == "cyber_security") cyberBlocked = true
            }
        }
        when {
            cyberBlocked -> OpenAiFailureReason.CyberSecurityBlocked
            blocked -> OpenAiFailureReason.ModerationBlocked
            else -> null
        }
    }
    is ResponsesStreamEvent.Failed -> if (
        response.error?.code == "content_policy_violation" ||
        response.error?.type == "content_policy_violation"
    ) OpenAiFailureReason.ContentPolicyViolation else null
    is ResponsesStreamEvent.Incomplete -> if (
        response.error?.code == "content_policy_violation" ||
        response.error?.type == "content_policy_violation"
    ) OpenAiFailureReason.ContentPolicyViolation else null
    is ResponsesStreamEvent.Other -> {
        val payload = payload as? JsonObject
        val type = (payload?.get("type") as? JsonPrimitive)?.contentOrNull
        val code = (payload?.get("code") as? JsonPrimitive)?.contentOrNull
        val error = payload?.get("error") as? JsonObject
        val errorCode = (error?.get("code") as? JsonPrimitive)?.contentOrNull
        val errorType = (error?.get("type") as? JsonPrimitive)?.contentOrNull
        if (type == "error" && listOf(code, errorCode, errorType).any { it == "content_policy_violation" }) {
            OpenAiFailureReason.ContentPolicyViolation
        } else {
            null
        }
    }
    else -> null
}

public actual fun ResponsesStreamEvent.toFailureDiagnostic(): OpenAiFailureDiagnostic? = when (this) {
    is ResponsesStreamEvent.Created -> response.missingTerminalDiagnostic()
    is ResponsesStreamEvent.InProgress -> response.missingTerminalDiagnostic()
    is ResponsesStreamEvent.Failed -> OpenAiFailureDiagnostic(
        termination = OpenAiFailureTermination.ResponseFailed,
        reason = safeFailureReason(response.error?.code) ?: safeFailureReason(response.error?.type),
        responseId = response.id?.takeIf { it.isNotEmpty() },
        errorCode = response.error?.code,
        errorType = response.error?.type,
        errorMessage = response.error?.message,
        incompleteReason = response.incompleteDetails?.reason,
    )
    is ResponsesStreamEvent.Incomplete -> OpenAiFailureDiagnostic(
        termination = OpenAiFailureTermination.ResponseIncomplete,
        reason = safeFailureReason(response.error?.code) ?: safeFailureReason(response.error?.type)
            ?: safeFailureReason(response.incompleteDetails?.reason),
        responseId = response.id?.takeIf { it.isNotEmpty() },
        errorCode = response.error?.code,
        errorType = response.error?.type,
        errorMessage = response.error?.message,
        incompleteReason = response.incompleteDetails?.reason,
    )
    else -> null
}

private fun Response.missingTerminalDiagnostic(): OpenAiFailureDiagnostic = OpenAiFailureDiagnostic(
    termination = OpenAiFailureTermination.MissingTerminal,
    reason = safeFailureReason(error?.code) ?: safeFailureReason(error?.type)
        ?: safeFailureReason(incompleteDetails?.reason),
    responseId = id.takeIf { it.isNotEmpty() },
    errorCode = error?.code,
    errorType = error?.type,
    errorMessage = error?.message,
    incompleteReason = incompleteDetails?.reason,
)

internal fun safeFailureReason(value: String?): OpenAiFailureReason? = when (value) {
    "content_policy_violation" -> OpenAiFailureReason.ContentPolicyViolation
    "server_error" -> OpenAiFailureReason.ServerError
    "rate_limit_exceeded" -> OpenAiFailureReason.RateLimitExceeded
    "insufficient_quota" -> OpenAiFailureReason.InsufficientQuota
    "invalid_request_error" -> OpenAiFailureReason.InvalidRequest
    "authentication_error" -> OpenAiFailureReason.AuthenticationError
    "context_length_exceeded" -> OpenAiFailureReason.ContextLengthExceeded
    "max_output_tokens" -> OpenAiFailureReason.MaxOutputTokens
    "content_filter" -> OpenAiFailureReason.ContentFilter
    else -> null
}
