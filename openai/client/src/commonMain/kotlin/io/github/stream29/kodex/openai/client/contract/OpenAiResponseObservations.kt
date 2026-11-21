package io.github.stream29.kodex.openai.client.contract

import io.github.stream29.kodex.openai.ResponsesStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

public actual fun Flow<ResponsesStreamEvent>.withReadObservations(): Flow<ObservedResponseEvent> =
    (this as? OpenAiResponseObservationSource)?.eventsWithObservations ?: map { ObservedResponseEvent(it) }
