@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.stream29.kodex.utils.ktorclientext.kodexcurl

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareGet
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.util.Attributes
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.*
import kotlinx.io.IOException
import libcurl.curl_slist_append
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

val kodexCurlLifetimeTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("repeated close cannot finish original owner before native cleanup gate") {
        val owner = Job()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var releases = 0
        val processor = KodexCurlProcessor(owner + Dispatchers.Default) {
            releases++
            entered.complete(Unit)
            release.await()
        }
        try {
            assertTrue(owner.children.any(), "Processor loop is not an engine child")
            processor.close()
            processor.close()
            withTimeout(5.seconds) { entered.await() }
            coroutineScope {
                val join = async(start = CoroutineStart.UNDISPATCHED) { owner.cancelAndJoin() }
                assertFalse(join.isCompleted)
                assertFalse(processor.cleanupCompleted.isCompleted)
                release.complete(Unit)
                withTimeout(5.seconds) { join.await(); processor.cleanupCompleted.await() }
            }
            assertEquals(1, releases)
            assertTrue(owner.children.none())
        } finally {
            release.complete(Unit)
            processor.close()
            withContext(NonCancellable) { owner.cancelAndJoin() }
        }
    }

    test("parent-only cancellation preserves primary and still releases after cleanup failure") {
        val owner = Job()
        val primary = CancellationException("fixture engine owner cancellation")
        val cleanup = IOException("fixture before native release")
        val processor = KodexCurlProcessor(owner + Dispatchers.Default) { throw cleanup }
        try {
            owner.cancel(primary) // No explicit processor.close path.
            withTimeout(5.seconds) { owner.join() }
            assertSame(cleanup, assertFailsWith<IOException> { processor.cleanupCompleted.await() })
            assertTrue(primary.suppressedExceptions.any { it === cleanup })
            assertTrue(owner.children.none())
        } finally {
            processor.close()
            withContext(NonCancellable) { owner.cancelAndJoin() }
        }
    }

    test("cancellation during native release preserves primary and suppressed cleanup failure") {
        val owner = Job()
        val primary = CancellationException("fixture cancellation during release")
        val cleanup = IOException("fixture release failure after cancellation")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val processor = KodexCurlProcessor(owner + Dispatchers.Default) {
            entered.complete(Unit)
            release.await()
            throw cleanup
        }
        try {
            processor.close()
            withTimeout(5.seconds) { entered.await() }
            owner.cancel(primary)
            assertFalse(owner.isCompleted)
            release.complete(Unit)
            withTimeout(5.seconds) { owner.join() }
            assertSame(cleanup, assertFailsWith<IOException> { processor.cleanupCompleted.await() })
            assertTrue(primary.suppressedExceptions.any { it === cleanup })
            assertTrue(owner.children.none())
        } finally {
            release.complete(Unit)
            processor.close()
            withContext(NonCancellable) { owner.cancelAndJoin() }
        }
    }

    test("original owner includes a real paused response and native API release") {
        // Ktor 3.5 uses a 1 MiB channel; exceed that real capacity.
        withNativeHttpFixture(ByteArray(1_310_720) { 'x'.code.toByte() }, 2_097_152) { fixture ->
            val owner = Job()
            val callOwner = Job(owner)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val processor = KodexCurlProcessor(owner + Dispatchers.Default) {
                entered.complete(Unit)
                release.await()
            }
            try {
                val request = HttpRequestBuilder().apply { url(fixture.url) }.build()
                    .toKodexCurlRequest(KodexCurlEngineConfig(), callOwner)
                val response = withTimeout(5.seconds) { processor.executeRequest(request) }
                val body = assertIs<KodexCurlHttpResponseBody>(response.responseBody)
                withTimeout(5.seconds) {
                    while (!body.isPausedForBackpressure) delay(1.milliseconds)
                }
                owner.cancel()
                withTimeout(5.seconds) { entered.await() }
                assertFalse(owner.isCompleted)
                release.complete(Unit)
                withTimeout(5.seconds) { owner.join(); processor.cleanupCompleted.await() }
                assertTrue(body.bodyChannel.isClosedForWrite)
                assertNotNull(body.bodyChannel.closedCause)
                assertTrue(owner.children.none())
            } finally {
                release.complete(Unit)
                fixture.closePeerAfterHeaders()
                processor.close()
                withContext(NonCancellable) { owner.cancelAndJoin() }
            }
        }
    }

    test("real HttpClient close waits through original engine owner without self join") {
        withNativeHttpFixture("data: alive\n\n".encodeToByteArray(), 1_048_576) { fixture ->
            val client = HttpClient(KodexCurl)
            val engine = assertIs<KodexCurlClientEngine>(client.engine)
            val engineOwner = engine.coroutineContext[Job]!!
            try {
                client.prepareGet(fixture.url).execute { response ->
                    withTimeout(5.seconds) { fixture.sent.await(); response.bodyAsChannel().awaitContent() }
                    client.close()
                    client.close()
                    // Ktor HttpClient.close is graceful: its current call must
                    // finish before the managed engine's close is invoked.
                }
                withTimeout(5.seconds) {
                    client.coroutineContext[Job]!!.join()
                    engineOwner.join()
                    engine.cleanupCompleted.await()
                }
                assertTrue(engineOwner.children.none())
            } finally {
                fixture.closePeerAfterHeaders()
                client.close()
                withContext(NonCancellable) { withTimeout(5.seconds) { engineOwner.join() } }
            }
        }
    }

    test("closed admission disposes native request headers before they can enter queue") {
        val owner = Job()
        val processor = KodexCurlProcessor(owner + Dispatchers.Default)
        try {
            processor.close()
            withTimeout(5.seconds) { processor.cleanupCompleted.await() }
            val callOwner = Job()
            try {
                val request = HttpRequestBuilder().apply { url("http://127.0.0.1:1/") }.build()
                    .toKodexCurlRequest(KodexCurlEngineConfig(), callOwner)
                assertFailsWith<Throwable> { processor.executeRequest(request) }
                assertFailsWith<IllegalStateException> { request.takeHeaders() }
            } finally {
                callOwner.cancelAndJoin()
            }
        } finally {
            processor.close()
            withContext(NonCancellable) { owner.cancelAndJoin() }
        }
    }

    test("native startup failure cancels current task and suppresses cleanup failure") {
        val owner = Job()
        val captured = CompletableDeferred<Throwable>()
        val cleanup = IOException("fixture native cleanup fault")
        val processor = KodexCurlProcessor(
            owner + Dispatchers.Default + CoroutineExceptionHandler { _, failure -> captured.complete(failure) },
        ) { throw cleanup }
        val callOwner = Job()
        // Missing WebSocket config fails *after* curl_easy_init, before native
        // admission. It is not a fake engine or an external endpoint.
        val request = KodexCurlRequestData(
            protocol = "http",
            url = "http://127.0.0.1:1/",
            method = "GET",
            headers = checkNotNull(curl_slist_append(null, "Accept: */*")),
            proxy = null,
            content = ByteReadChannel.Empty,
            contentLength = 0,
            connectTimeout = 1_000,
            socketTimeout = null,
            callContext = callOwner,
            isUpgradeRequest = true,
            forceProxyTunneling = false,
            sslVerify = true,
            caInfo = null,
            caPath = null,
            attributes = Attributes(),
        )
        try {
            val primary = assertFailsWith<IllegalStateException> {
                withTimeout(5.seconds) { processor.executeRequest(request) }
            }
            withTimeout(5.seconds) { owner.join() }
            assertSame(primary, withTimeout(5.seconds) { captured.await() })
            assertTrue(primary.suppressedExceptions.any { it === cleanup })
            assertSame(cleanup, assertFailsWith<IOException> { processor.cleanupCompleted.await() })
            assertFailsWith<IllegalStateException> { request.takeHeaders() }
            assertTrue(owner.children.none())
        } finally {
            request.dispose()
            callOwner.cancelAndJoin()
            processor.close()
            withContext(NonCancellable) { owner.cancelAndJoin() }
        }
    }
}
