package io.github.stream29.kodex.openai.client

import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.OpenAiFailureDiagnostic
import io.github.stream29.kodex.openai.OpenAiFailureDiagnosticCarrier
import io.github.stream29.kodex.openai.OpenAiFailureReason
import io.github.stream29.kodex.openai.OpenAiFailureTermination
import kotlinx.io.IOException

public class OpenAiModerationBlockedException(
    reason: OpenAiFailureReason? = null,
    httpStatus: Int? = null,
    requestId: String? = null,
    responseId: String? = null,
    diagnostic: OpenAiFailureDiagnostic? = null,
) : IllegalStateException(
    when (reason) {
        OpenAiFailureReason.CyberSecurityBlocked -> "OpenAI blocked the request under its cybersecurity moderation policy."
        OpenAiFailureReason.ContentPolicyViolation ->
            "OpenAI moderation rejected the request for a content policy violation."
        else -> "OpenAI rejected the request due to a content policy or moderation block."
    },
), OpenAiFailureDiagnosticCarrier {
    override var failureDiagnostic: OpenAiFailureDiagnostic? =
        (diagnostic ?: OpenAiFailureDiagnostic(OpenAiFailureTermination.ModerationBlocked)).copy(
            termination = OpenAiFailureTermination.ModerationBlocked,
            httpStatus = httpStatus,
            reason = reason,
            requestId = requestId,
            responseId = responseId,
        )
        internal set
    override var retries: Int? = null
        internal set
    override var operationId: Long? = null
        internal set
}

public expect fun ResponsesStreamEvent.isExplicitModerationBlock(): Boolean

/** Fixed reason for an explicit structured block, or null for unknown metadata. */
public expect fun ResponsesStreamEvent.explicitModerationReason(): OpenAiFailureReason?

/** Preserves selected structured provider fields alongside stable local categories. */
public expect fun ResponsesStreamEvent.toFailureDiagnostic(): OpenAiFailureDiagnostic?

/** Retryable streaming transport failure with structured provider diagnostics. */
public class OpenAiRetryableStreamException(
    public val diagnostic: OpenAiFailureDiagnostic,
) : IOException("OpenAI streaming transport failed."), OpenAiFailureDiagnosticCarrier {
    override var failureDiagnostic: OpenAiFailureDiagnostic? = diagnostic
        internal set
    override var retries: Int? = null
        internal set
    override var operationId: Long? = null
        internal set
}

/** HTTP failure without provider-controlled exception text. */
public class OpenAiHttpStreamException(
    public val status: Int,
    requestId: String? = null,
) : IllegalStateException("OpenAI streaming request failed (HTTP $status)."), OpenAiFailureDiagnosticCarrier {
    override var failureDiagnostic: OpenAiFailureDiagnostic? =
        OpenAiFailureDiagnostic(OpenAiFailureTermination.HttpError, httpStatus = status, requestId = requestId)
        internal set
    override var retries: Int? = null
        internal set
    override var operationId: Long? = null
        internal set
}

/** A malformed or otherwise unrecognized stream failure, with no raw payload. */
public class OpenAiStreamException(
    httpStatus: Int? = null,
) : IllegalStateException("OpenAI response stream failed."),
    OpenAiFailureDiagnosticCarrier {
    override var failureDiagnostic: OpenAiFailureDiagnostic? =
        OpenAiFailureDiagnostic(
            OpenAiFailureTermination.StreamError,
            httpStatus = httpStatus,
        )
        internal set
    override var retries: Int? = null
        internal set
    override var operationId: Long? = null
        internal set
}
