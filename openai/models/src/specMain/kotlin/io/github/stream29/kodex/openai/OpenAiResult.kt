package io.github.stream29.kodex.openai

/**
 * Returns the successful payload or raises the structured OpenAI error.
 *
 * @throws OpenAiResponseResultException when this result is a provider
 * [OpenAiResult.Failure].
 */
public expect fun <Success> OpenAiResponseResult<Success>.getOrThrow(): Success
