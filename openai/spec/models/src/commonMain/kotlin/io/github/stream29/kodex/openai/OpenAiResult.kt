package io.github.stream29.kodex.openai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform

/**
 * Returns the successful payload or raises the structured OpenAI error.
 *
 * @throws OpenAiResponseResultException when this result is a provider
 * [OpenAiResult.Failure].
 */
public fun <Success> OpenAiResponseResult<Success>.getOrThrow(): Success =
    when (this) {
        is OpenAiResult.Success -> value
        is OpenAiResult.Failure -> throw OpenAiResponseResultException(error)
    }

/**
 * Emits every stream event and raises [OpenAiResponseStreamFailureException]
 * immediately after a `response.failed` event reaches the caller.
 *
 * @throws OpenAiResponseStreamFailureException when a failed response event
 * is collected, after that event has been emitted.
 */
public fun Flow<ResponsesStreamEvent>.throwIfFailure(): Flow<ResponsesStreamEvent> =
    transform { event ->
        emit(event)
        if (event is ResponsesStreamEvent.Failed) {
            throw OpenAiResponseStreamFailureException(event.response.error)
        }
    }
