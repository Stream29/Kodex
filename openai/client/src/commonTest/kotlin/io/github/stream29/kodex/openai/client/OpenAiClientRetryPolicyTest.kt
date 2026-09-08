package io.github.stream29.kodex.openai.client

import de.infix.testBalloon.framework.core.testSuite
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val openAiClientRetryPolicyTest by testSuite {
    test("retryable statuses are transient only") {
        val retry = OpenAiClientRetryConfig()

        assertTrue(HttpStatusCode.RequestTimeout.isRetryableOpenAiStatus(retry))
        assertTrue(HttpStatusCode.TooManyRequests.isRetryableOpenAiStatus(retry))
        assertTrue(HttpStatusCode.InternalServerError.isRetryableOpenAiStatus(retry))
        assertTrue(HttpStatusCode.ServiceUnavailable.isRetryableOpenAiStatus(retry))

        assertFalse(HttpStatusCode.Forbidden.isRetryableOpenAiStatus(retry))
        assertFalse(HttpStatusCode.NotFound.isRetryableOpenAiStatus(retry))
        assertFalse(HttpStatusCode.BadRequest.isRetryableOpenAiStatus(retry))
    }

    test("retryable statuses respect category switches") {
        val retry = OpenAiClientRetryConfig(
            retryRateLimited = false,
            retryServerErrors = false,
            retryTransport = false,
        )

        assertFalse(HttpStatusCode.RequestTimeout.isRetryableOpenAiStatus(retry))
        assertFalse(HttpStatusCode.TooManyRequests.isRetryableOpenAiStatus(retry))
        assertFalse(HttpStatusCode.InternalServerError.isRetryableOpenAiStatus(retry))
    }

    test("retryable exceptions are transport only") {
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

    test("HTTP and SSE rate limit exceptions use the HTTP retry category") {
        HttpClient(MockEngine { respond("rate limited", HttpStatusCode.TooManyRequests) }).use { client ->
            val response = client.get("https://example.test/responses")
            val httpFailure = ClientRequestException(response, "rate limited")
            val failures = listOf(
                httpFailure,
                SSEClientException(response = response),
                SSEClientException(cause = httpFailure),
                SSEClientException(cause = SSEClientException(response = response)),
            )
            for (failure in failures) {
                assertTrue(failure.isRetryableOpenAiException(OpenAiClientRetryConfig(retryTransport = false)))
                assertFalse(failure.isRetryableOpenAiException(OpenAiClientRetryConfig(retryRateLimited = false)))
            }
            assertFalse(
                SSEClientException(response = response, cause = CancellationException("cancelled"))
                    .isRetryableOpenAiException(OpenAiClientRetryConfig()),
            )
        }
    }
}
