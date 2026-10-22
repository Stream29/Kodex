package io.github.stream29.kodex.openai

import kotlinx.coroutines.flow.Flow

/**
 * Returns the successful payload or raises the structured OpenAI error.
 *
 * @throws OpenAiResponseResultException when this result is a provider
 * [OpenAiResult.Failure].
 */
public expect fun <Success> OpenAiResponseResult<Success>.getOrThrow(): Success

/**
 * Emits every stream event and raises [OpenAiResponseStreamFailureException]
 * immediately after a `response.failed` event reaches the caller.
 *
 * @throws OpenAiResponseStreamFailureException when a failed response event
 * is collected, after that event has been emitted.
 */
public expect fun Flow<ResponsesStreamEvent>.throwIfFailure(): Flow<ResponsesStreamEvent>
