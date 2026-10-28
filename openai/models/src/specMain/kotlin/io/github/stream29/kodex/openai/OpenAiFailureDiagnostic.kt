package io.github.stream29.kodex.openai

/**
 * Failure projection with local categories and selected provider fields.
 * Missing fields remain unknown; the complete response is not retained here.
 */
public data class OpenAiFailureDiagnostic(
    public val termination: OpenAiFailureTermination,
    public val httpStatus: Int? = null,
    public val reason: OpenAiFailureReason? = null,
    public val requestId: String? = null,
    public val responseId: String? = null,
    public val errorCode: String? = null,
    public val errorType: String? = null,
    public val errorMessage: String? = null,
    public val incompleteReason: String? = null,
) {
    override fun toString(): String =
        "OpenAiFailureDiagnostic(termination=$termination, httpStatus=$httpStatus, reason=$reason, " +
            "requestId=${requestId?.let(::escapeOpenAiDiagnosticText)}, " +
            "responseId=${responseId?.let(::escapeOpenAiDiagnosticText)}, " +
            "errorCode=${errorCode?.let(::escapeOpenAiDiagnosticText)}, " +
            "errorType=${errorType?.let(::escapeOpenAiDiagnosticText)}, " +
            "errorMessage=${errorMessage?.let(::escapeOpenAiDiagnosticText)}, " +
            "incompleteReason=${incompleteReason?.let(::escapeOpenAiDiagnosticText)})"
}

/** Reversibly escape controls and backslashes without changing ordinary text. */
public fun escapeOpenAiDiagnosticText(value: String): String = buildString {
    value.forEach { char ->
        when {
            char == '\\' -> append("\\\\")
            char.code < 0x20 || char.code in 0x7f..0x9f || char.code in 0x2028..0x2029 ->
                append("\\u").append(char.code.toString(16).padStart(4, '0'))
            else -> append(char)
        }
    }
}

/** Locally defined categories, never arbitrary provider text. */
public enum class OpenAiFailureTermination {
    ResponseFailed,
    ResponseIncomplete,
    MissingTerminal,
    HttpError,
    TransportError,
    StreamError,
    ModerationBlocked,
}

/** Explicitly recognized protocol reasons; all other values remain unknown. */
public enum class OpenAiFailureReason {
    ContentPolicyViolation,
    ModerationBlocked,
    CyberSecurityBlocked,
    ServerError,
    RateLimitExceeded,
    InsufficientQuota,
    InvalidRequest,
    AuthenticationError,
    ContextLengthExceeded,
    MaxOutputTokens,
    ContentFilter,
}

/** Selected failure metadata propagated without retaining the complete response. */
public interface OpenAiFailureDiagnosticCarrier {
    public val failureDiagnostic: OpenAiFailureDiagnostic?
    public val retries: Int?
    public val operationId: Long?
}
