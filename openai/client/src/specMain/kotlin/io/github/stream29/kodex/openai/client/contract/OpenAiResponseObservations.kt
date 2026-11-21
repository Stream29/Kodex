package io.github.stream29.kodex.openai.client.contract

import io.github.stream29.kodex.openai.ResponsesStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/** A protocol event paired with any observations made by the client that received it. */
public data class ObservedResponseEvent(
    public val event: ResponsesStreamEvent,
    public val completion: ObservedResponseCompletion? = null,
)

/** Missing timing or reported usage is not a measured zero. These fields are not provider wire fields. */
public data class ObservedResponseCompletion(
    public val elapsed: Duration?,
    public val reportedOutputTokens: Long?,
    public val reportedTotalTokens: Long?,
)

/** The observed view of the same cold stream, not a separate request to collect alongside it. */
public interface OpenAiResponseObservationSource {
    public val eventsWithObservations: Flow<ObservedResponseEvent>
}

/** Exposes client observations when available; otherwise preserves wire events without inventing observations. */
public expect fun Flow<ResponsesStreamEvent>.withReadObservations(): Flow<ObservedResponseEvent>
