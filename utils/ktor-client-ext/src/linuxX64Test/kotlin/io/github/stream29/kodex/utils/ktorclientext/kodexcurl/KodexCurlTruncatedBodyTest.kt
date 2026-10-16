package io.github.stream29.kodex.utils.ktorclientext.kodexcurl

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** B2 regression gate: no completion/body algorithm change before native reproduction. */
val kodexCurlTruncatedBodyTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("postHeaderTruncationMustNotBecomeSuccessfulEof") {
        withNativeHttpFixture("data: partial\n\n".encodeToByteArray(), 100) { fixture ->
            withFixtureClient { client ->
                client.prepareGet(fixture.url).execute { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    val body = response.bodyAsChannel()
                    withTimeout(5.seconds) { fixture.sent.await(); body.awaitContent() }
                    // The client has really received headers and partial body
                    // before the fixture closes its own socket.
                    fixture.closePeerAfterHeaders()
                    assertFailsWith<IOException> {
                        withTimeout(5.seconds) { body.readRemaining() }
                    }
                }
            }
        }
    }

    test("postHeaderSocketIdleTimeoutRemainsSeparateFromTruncation") {
        withNativeHttpFixture("data: partial\n\n".encodeToByteArray(), 100) { fixture ->
            withFixtureClient(socketTimeoutMillis = 200) { client ->
                client.prepareGet(fixture.url).execute { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    withTimeout(5.seconds) { fixture.sent.await() }
                    val failure = assertFailsWith<IOException> {
                        withTimeout(5.seconds) { response.bodyAsChannel().readRemaining() }
                    }
                    assertTrue(generateSequence<Throwable>(failure) { it.cause }
                        .any { it is SocketTimeoutException }, "Socket timeout must remain the channel failure cause")
                }
            }
        }
    }
}

private suspend fun withFixtureClient(
    socketTimeoutMillis: Long? = null,
    block: suspend (HttpClient) -> Unit,
) {
    val client = HttpClient(KodexCurl) {
        install(HttpTimeout) {
            connectTimeoutMillis = 1_000
            this.socketTimeoutMillis = socketTimeoutMillis
        }
    }
    val owner = client.engine.coroutineContext[Job]!!
    try {
        block(client)
    } finally {
        client.close()
        withContext(NonCancellable) { withTimeout(5.seconds) { owner.join() } }
    }
}
