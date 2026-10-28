package io.github.stream29.kodex.openai.client

import kotlinx.io.IOException
import io.github.stream29.kodex.openai.OpenAiFailureDiagnostic
import io.github.stream29.kodex.openai.OpenAiFailureDiagnosticCarrier

/** Remote compaction emitted an invalid protocol event or output shape. */
public class OpenAiRemoteCompactionV2ProtocolException(
    message: String,
) : IllegalStateException(message)

/** Remote compaction's stream closed before producing compaction output. */
public class OpenAiRemoteCompactionV2StreamIncompleteException : IOException(
    "Remote compaction v2 stream closed before compaction output.",
), OpenAiFailureDiagnosticCarrier {
    override var failureDiagnostic: OpenAiFailureDiagnostic? = null
        internal set
    override var retries: Int? = null
        internal set
    override var operationId: Long? = null
        internal set
}

/** Remote compaction's stream reported a retryable failure. */
public class OpenAiRemoteCompactionV2StreamFailureException : IOException(
    "Remote compaction v2 stream reported a retryable failure.",
), OpenAiFailureDiagnosticCarrier {
    override var failureDiagnostic: OpenAiFailureDiagnostic? = null
        internal set
    override var retries: Int? = null
        internal set
    override var operationId: Long? = null
        internal set
}
