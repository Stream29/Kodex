package io.github.stream29.kodex.openai.client.test

import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.client.contract.ObservedResponseCompletion
import io.github.stream29.kodex.openai.client.contract.ObservedResponseEvent
import io.github.stream29.kodex.openai.client.contract.OpenAiResponseObservationSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
public actual fun Flow<ResponsesStreamEvent>.withFixtureResponseReadTiming(elapsed: Duration): Flow<ResponsesStreamEvent> {
    require(elapsed.isFinite() && elapsed.isPositive())
    val wire = this
    return object : Flow<ResponsesStreamEvent> by wire, OpenAiResponseObservationSource {
        override val eventsWithObservations = flow {
            var startId: String? = null
            wire.collect { event ->
                val completion = when (event) {
                    is ResponsesStreamEvent.Created -> {
                        startId = event.response.id
                        null
                    }
                    is ResponsesStreamEvent.Completed -> {
                        val matches = event.response.id == startId
                        startId = null
                        if (matches) ObservedResponseCompletion(
                            elapsed,
                            event.response.usage?.outputTokens,
                            event.response.usage?.totalTokens,
                        ) else null
                    }
                    is ResponsesStreamEvent.Failed, is ResponsesStreamEvent.Incomplete -> {
                        startId = null
                        null
                    }
                    else -> null
                }
                emit(ObservedResponseEvent(event, completion))
            }
        }
    }
}
