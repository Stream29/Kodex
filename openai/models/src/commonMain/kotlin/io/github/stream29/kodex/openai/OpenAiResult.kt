package io.github.stream29.kodex.openai

/**
 * Returns the successful payload or raises the structured OpenAI error.
 *
 * @throws OpenAiResponseResultException when this result is a provider
 * [OpenAiResult.Failure].
 */
public actual fun <Success> OpenAiResponseResult<Success>.getOrThrow(): Success =
    when (this) {
        is OpenAiResult.Success -> value
        is OpenAiResult.Failure -> throw OpenAiResponseResultException(error)
    }
