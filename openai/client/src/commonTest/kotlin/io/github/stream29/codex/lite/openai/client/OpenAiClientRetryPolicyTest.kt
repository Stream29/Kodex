package io.github.stream29.codex.lite.openai.client

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiClientRetryPolicyTest {
    @Test
    fun retryableStatusesAreTransientOnly() {
        val retry = OpenAiClientRetryConfig()

        assertTrue(HttpStatusCode.RequestTimeout.isRetryableOpenAiStatus(retry))
        assertTrue(HttpStatusCode.TooManyRequests.isRetryableOpenAiStatus(retry))
        assertTrue(HttpStatusCode.InternalServerError.isRetryableOpenAiStatus(retry))
        assertTrue(HttpStatusCode.ServiceUnavailable.isRetryableOpenAiStatus(retry))

        assertFalse(HttpStatusCode.Forbidden.isRetryableOpenAiStatus(retry))
        assertFalse(HttpStatusCode.NotFound.isRetryableOpenAiStatus(retry))
        assertFalse(HttpStatusCode.BadRequest.isRetryableOpenAiStatus(retry))
    }

    @Test
    fun retryableStatusesRespectCategorySwitches() {
        val retry = OpenAiClientRetryConfig(
            retryRateLimited = false,
            retryServerErrors = false,
            retryTransport = false,
        )

        assertFalse(HttpStatusCode.RequestTimeout.isRetryableOpenAiStatus(retry))
        assertFalse(HttpStatusCode.TooManyRequests.isRetryableOpenAiStatus(retry))
        assertFalse(HttpStatusCode.InternalServerError.isRetryableOpenAiStatus(retry))
    }

    @Test
    fun retryableExceptionsAreTransportOnly() {
        val retry = OpenAiClientRetryConfig()

        assertTrue(IOException("network").isRetryableOpenAiTransportException(retry))
        assertFalse(IllegalStateException("decode").isRetryableOpenAiTransportException(retry))
        assertFalse(CancellationException("cancelled").isRetryableOpenAiTransportException(retry))
        assertFalse(
            IOException("network").isRetryableOpenAiTransportException(
                retry.copy(retryTransport = false),
            ),
        )
    }
}
