package io.github.stream29.kodex.agentruntime.decorator.compact

/**
 * Raised after every retry allowed for one Responses sampling request has also
 * returned a retryable result.
 *
 * [maxRetries] reports the limit used by the implementation that raised it.
 */
public class AgentResponseRetryLimitExceededException(
    public val maxRetries: Int,
) : IllegalStateException(
    "Agent response request failed after $maxRetries retries.",
)
