@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.utils.oauthloopback

import de.infix.testBalloon.framework.core.testSuite
import io.ktor.http.Parameters
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

public val oauthLoopbackTest by testSuite {
    test("candidate validation retains HTTP host and path rules without disclosing rejected input") {
        val fixed = "http://localhost:1455/auth/callback"
        assertEquals(fixed, parseRedirect(fixed).toString())
        assertEquals(1455, callbackBindPort(fixed, parseRedirect(fixed)))
        val ephemeral = "http://127.0.0.1:0/call%20back"
        assertEquals("127.0.0.1", parseRedirect(ephemeral).host)
        assertEquals("/call%20back", parseRedirect(ephemeral).encodedPath)
        assertEquals(0, callbackBindPort(ephemeral, parseRedirect(ephemeral)))
        val defaultPort = "http://127.0.0.1/callback"
        assertEquals(80, callbackBindPort(defaultPort, parseRedirect(defaultPort)))
        for (candidate in listOf(
            "https://localhost:1455/private-code",
            "http://example.invalid:1455/private-code",
            "http://[::1]:1455/private-code",
            "http://private-code@localhost:1455/callback",
            "http://user:private-code@127.0.0.1:1455/callback",
            "http://localhost:1455/callback?code=private-code",
            "http://localhost:1455/callback#private-code",
            "http://localhost:private-code/callback",
        )) {
            val failure = assertFailsWith<IllegalArgumentException> { parseRedirect(candidate) }
            assertFalse(failure.toString().contains("private-code"))
            assertEquals(null, failure.cause)
        }
    }

    test("unarmed malformed and repeated callbacks are rejected and raw query is retained once") {
        runTest {
            withLoopbackOwner { owner ->
                lateinit var engine: FakeLoopbackEngine
                val listener = openLoopback(owner, listOf("http://localhost:1455/auth/callback")) { scope, url, receive ->
                    FakeLoopbackEngine(scope, url, receive).also { engine = it }
                }
                assertFalse(engine.request("state=expected&code=private-code"))
                listener.armState("expected")
                for (query in listOf(
                    "code=private-code",
                    "state=wrong&code=private-code",
                    "state=expected&state=expected&code=private-code",
                    "state=expected",
                    "state=expected&code=a&code=b",
                    "state=expected&error=a&error=b",
                    "state=expected&code=a&error=b",
                )) {
                    assertFalse(engine.request(query))
                }
                val raw = "code=private%2Bcode&state=expected&extra=a%20b"
                assertTrue(engine.request(raw))
                assertFalse(engine.request("state=expected&code=second"))
                assertFalse(engine.request("state=expected&error=second"))
                assertEquals("${listener.redirectUri}?$raw", listener.awaitCallback())
                assertFailsWith<IllegalStateException> { listener.awaitCallback() }
                assertFailsWith<IllegalStateException> { listener.armState("replacement") }
                listener.closeAndJoin()
                assertEquals(1, engine.stops)
                assertTrue(owner.isActive)
            }
        }
    }

    test("state replacement and provider errors remain mechanical and empty code or error is accepted") {
        runTest {
            withLoopbackOwner { owner ->
                for (result in listOf("code=", "error=", "error=access_denied&error_description=private-description")) {
                    lateinit var engine: FakeLoopbackEngine
                    val listener = openLoopback(owner, listOf("http://127.0.0.1:1455/callback")) { scope, url, receive ->
                        FakeLoopbackEngine(scope, url, receive).also { engine = it }
                    }
                    listener.armState("old")
                    listener.armState("new value")
                    assertFalse(engine.request("state=old&$result"))
                    val raw = "state=new+value&$result"
                    assertTrue(engine.request(raw))
                    assertEquals("${listener.redirectUri}?$raw", listener.awaitCallback())
                    listener.closeAndJoin()
                }
            }
        }
    }

    test("strict admission rejects blank values without consuming a later valid callback") {
        runTest {
            withLoopbackOwner { owner ->
                lateinit var engine: FakeLoopbackEngine
                val listener = openLoopback(owner, listOf("http://localhost:1455/callback")) { scope, url, receive ->
                    FakeLoopbackEngine(scope, url, receive).also { engine = it }
                }
                try {
                    listener.armState("expected", OAuthCallbackValuePolicy.RequireNonBlank)
                    assertFalse(engine.request("state=expected&code="))
                    assertFalse(engine.request("state=expected&code=%20%20"))
                    assertFalse(engine.request("state=expected&error="))
                    assertFalse(engine.request("state=expected&error=%20"))
                    assertTrue(engine.request("state=expected&code=valid"))
                    assertEquals("${listener.redirectUri}?state=expected&code=valid", listener.awaitCallback())
                } finally { listener.closeAndJoin() }
            }
        }
    }

    test("a claimed callback is observable immediately but close waits for its response boundary") {
        runTest {
            withLoopbackOwner { parent ->
                val child = SupervisorJob(parent.coroutineContext[Job])
                val stopped = CompletableDeferred(Unit)
                val listener = NativeLoopbackListener(child, stopped)
                val raw = "state=expected&code=fixture"
                try {
                    listener.setRedirect("http://localhost:1455/callback")
                    listener.armState("expected", OAuthCallbackValuePolicy.RequireNonBlank)
                    val completion = assertNotNull(listener.claim(Url("http://localhost/callback?$raw").parameters, raw))
                    val waiting = async { listener.awaitCallback() }
                    runCurrent()
                    assertTrue(waiting.isCompleted)
                    assertEquals("http://localhost:1455/callback?$raw", waiting.await())
                    assertNull(listener.claim(Url("http://localhost/callback?$raw").parameters, raw))
                    assertFailsWith<IllegalStateException> { listener.armState("new") }
                    val closing = async { listener.closeAndJoin() }
                    runCurrent()
                    assertFalse(closing.isCompleted)
                    completion()
                    closing.await()
                } finally { listener.closeAndJoin() }
            }
        }
    }

    test("admission rejects without consuming and accepts exactly once") {
        runTest {
            withLoopbackOwner { parent ->
                val child = SupervisorJob(parent.coroutineContext[Job])
                val listener = NativeLoopbackListener(child, CompletableDeferred(Unit))
                val raw = "state=expected&code=fixture"
                var permitted = false
                var attempts = 0
                try {
                    listener.setRedirect("http://localhost:1455/callback")
                    listener.armStateWithAdmission("expected", OAuthCallbackValuePolicy.RequireNonBlank) {
                        attempts++
                        permitted
                    }
                    assertNull(listener.claim(Url("http://localhost/callback?$raw").parameters, raw))
                    permitted = true
                    val response = assertNotNull(listener.claim(Url("http://localhost/callback?$raw").parameters, raw))
                    assertNull(listener.claim(Url("http://localhost/callback?$raw").parameters, raw))
                    assertEquals(2, attempts)
                    assertEquals("http://localhost:1455/callback?$raw", listener.awaitCallback())
                    response()
                } finally { listener.closeAndJoin() }
            }
        }
    }

    test("competing valid callbacks have exactly one winner") {
        runTest {
            withLoopbackOwner { owner ->
                lateinit var engine: FakeLoopbackEngine
                val listener = openLoopback(owner, listOf("http://localhost:1455/callback")) { scope, url, receive ->
                    FakeLoopbackEngine(scope, url, receive).also { engine = it }
                }
                listener.armState("expected")
                val first = async { engine.request("state=expected&code=first") }
                val second = async { engine.request("state=expected&error=second") }
                val firstWon = first.await()
                assertEquals(!firstWon, second.await())
                assertEquals(
                    "${listener.redirectUri}?state=expected&${if (firstWon) "code=first" else "error=second"}",
                    listener.awaitCallback(),
                )
                listener.closeAndJoin()
            }
        }
    }

    test("cancelling the sole wait does not permit a new wait or cancel the borrowed owner") {
        runTest {
            withLoopbackOwner { owner ->
                lateinit var engine: FakeLoopbackEngine
                val listener = openLoopback(owner, listOf("http://localhost:1455/callback")) { scope, url, receive ->
                    FakeLoopbackEngine(scope, url, receive).also { engine = it }
                }
                listener.armState("expected")
                val waiting = async { listener.awaitCallback() }
                runCurrent()
                waiting.cancelAndJoin()
                assertTrue(owner.isActive)
                assertEquals(0, engine.stops)
                assertFailsWith<IllegalStateException> { listener.awaitCallback() }
                assertTrue(engine.request("state=expected&code=private-code"))
                listener.closeAndJoin()
            }
        }
    }

    test("fallback fully stops its failed engine and preserves host spelling and requested port order") {
        runTest {
            withLoopbackOwner { owner ->
                val engines = mutableListOf<FakeLoopbackEngine>()
                val candidates = listOf("http://localhost:1455/auth/callback", "http://localhost:1457/auth/callback")
                val listener = openLoopback(owner, candidates) { scope, url, receive ->
                    if (engines.isNotEmpty()) assertEquals(1, engines.single().stops)
                    FakeLoopbackEngine(
                        scope, url, receive,
                        startBlock = {
                            if (url.port == 1455) error("private-code")
                            1457
                        },
                    ).also { engines += it }
                }
                assertEquals(listOf(1455, 1457), engines.map { it.url.port })
                assertEquals("http://localhost:1457/auth/callback", listener.redirectUri)
                assertNotSame(owner.coroutineContext[Job], engines.last().scope.coroutineContext[Job])
                listener.closeAndJoin()
                assertEquals(listOf(1, 1), engines.map { it.stops })
                assertTrue(owner.isActive)
                assertTrue(owner.coroutineContext[Job]!!.children.none())
            }
        }
    }

    test("engine-local cancellation and asynchronous bind failure both try the next supplied candidate") {
        runTest {
            withLoopbackOwner { owner ->
                for (childFailure in listOf(false, true)) {
                    var attempts = 0
                    val listener = openLoopback(
                        owner, listOf("http://localhost:1455/callback", "http://localhost:1457/callback"),
                    ) { scope, url, receive ->
                        FakeLoopbackEngine(scope, url, receive, startBlock = {
                            if (++attempts == 1) {
                                if (childFailure) {
                                    scope.launch { error("private-code") }
                                    awaitCancellation()
                                }
                                throw CancellationException("engine bind failure")
                            }
                            url.port
                        })
                    }
                    assertEquals(2, attempts)
                    listener.closeAndJoin()
                    assertTrue(owner.isActive)
                }
            }
        }
    }

    test("zero port uses the resolved port and a later invalid candidate is not visited") {
        runTest {
            withLoopbackOwner { owner ->
                val listener = openLoopback(
                    owner, listOf("http://127.0.0.1:0/call%20back", "https://example.invalid/private-code"),
                ) { scope, url, receive ->
                    assertEquals("/call%20back", url.encodedPath)
                    FakeLoopbackEngine(scope, url, receive, startBlock = { 54321 })
                }
                assertEquals("http://127.0.0.1:54321/call%20back", listener.redirectUri)
                listener.closeAndJoin()
            }
        }
    }

    test("exhaustion is sanitized and never invents a fallback for a single occupied candidate") {
        runTest {
            withLoopbackOwner { owner ->
                var attempts = 0
                var stops = 0
                val factory: LoopbackEngineFactory = { scope, url, receive ->
                    attempts++
                    FakeLoopbackEngine(
                        scope, url, receive,
                        startBlock = { error("private-code state=private-state") },
                        stopBlock = { stops++ },
                    )
                }
                val failure = assertFailsWith<IllegalStateException> {
                    openLoopback(owner, listOf("http://localhost:1455/callback"), factory)
                }
                assertFalse(failure.toString().contains("private"))
                assertEquals(null, failure.cause)
                assertEquals(1, attempts)
                assertEquals(1, stops)
                assertFailsWith<IllegalStateException> { openLoopback(owner, emptyList(), factory) }
                assertEquals(1, attempts)
                assertTrue(owner.isActive)
            }
        }
    }

    test("a reached invalid candidate stops fallback after prior engine cleanup") {
        runTest {
            withLoopbackOwner { owner ->
                var attempts = 0
                var stops = 0
                assertFailsWith<IllegalArgumentException> {
                    openLoopback(
                        owner,
                        listOf(
                            "http://localhost:1455/callback",
                            "http://localhost:1457/callback?code=private-code",
                            "http://localhost:1458/callback",
                        ),
                    ) { scope, url, receive ->
                        attempts++
                        FakeLoopbackEngine(scope, url, receive, startBlock = { error("bind") }, stopBlock = { stops++ })
                    }
                }
                assertEquals(1, attempts)
                assertEquals(1, stops)
                assertTrue(owner.coroutineContext[Job]!!.children.none())
            }
        }
    }

    test("caller cancellation during acquisition waits for cleanup without cancelling borrowed owner") {
        runTest {
            withLoopbackOwner { owner ->
                val entered = CompletableDeferred<Unit>()
                lateinit var engine: FakeLoopbackEngine
                val opening = async {
                    openLoopback(owner, listOf("http://localhost:1455/callback")) { scope, url, receive ->
                        FakeLoopbackEngine(scope, url, receive, startBlock = {
                            entered.complete(Unit)
                            awaitCancellation()
                        }).also { engine = it }
                    }
                }
                entered.await()
                opening.cancelAndJoin()
                assertEquals(1, engine.stops)
                assertTrue(owner.isActive)
                assertTrue(owner.coroutineContext[Job]!!.children.none())
            }
        }
    }

    test("caller cancellation at readiness releases the just acquired resource") {
        runTest {
            withLoopbackOwner { owner ->
                val caller = Job(coroutineContext[Job])
                lateinit var engine: FakeLoopbackEngine
                val opening = CoroutineScope(coroutineContext + caller).async {
                    openLoopback(owner, listOf("http://localhost:1455/callback")) { scope, url, receive ->
                        FakeLoopbackEngine(scope, url, receive, startBlock = {
                            caller.cancel()
                            1455
                        }).also { engine = it }
                    }
                }
                opening.join()
                caller.join()
                assertTrue(opening.isCancelled)
                assertEquals(1, engine.stops)
                assertTrue(owner.isActive)
                assertTrue(owner.coroutineContext[Job]!!.children.none())
            }
        }
    }

    test("an engine child failure is sanitized and terminates only the listener resource") {
        runTest {
            withLoopbackOwner { owner ->
                val fail = CompletableDeferred<Unit>()
                lateinit var engine: FakeLoopbackEngine
                val listener = openLoopback(owner, listOf("http://localhost:1455/callback")) { scope, url, receive ->
                    scope.launch {
                        fail.await()
                        error("code=private-code&state=private-state")
                    }
                    FakeLoopbackEngine(scope, url, receive).also { engine = it }
                }
                fail.complete(Unit)
                runCurrent()
                val failure = assertFailsWith<IllegalStateException> { listener.awaitCallback() }
                assertFalse(failure.toString().contains("private"))
                assertEquals(null, failure.cause)
                listener.closeAndJoin()
                assertEquals(1, engine.stops)
                assertTrue(owner.isActive)
                assertTrue(owner.coroutineContext[Job]!!.children.none())
            }
        }
    }

    test("borrowed owner cancellation while binding cleans up and does not visit another candidate") {
        runTest {
            withLoopbackOwner { owner ->
                val entered = CompletableDeferred<Unit>()
                var attempts = 0
                var stops = 0
                val opening = async {
                    openLoopback(
                        owner, listOf("http://localhost:1455/callback", "http://localhost:1457/callback"),
                    ) { scope, url, receive ->
                        attempts++
                        FakeLoopbackEngine(scope, url, receive, startBlock = {
                            entered.complete(Unit)
                            awaitCancellation()
                        }, stopBlock = { stops++ })
                    }
                }
                entered.await()
                owner.coroutineContext[Job]!!.cancelAndJoin()
                assertFailsWith<CancellationException> { opening.await() }
                assertEquals(1, attempts)
                assertEquals(1, stops)
            }
        }
    }

    test("owner completion releases the engine and cancels the pending callback without explicit close") {
        runTest {
            withLoopbackOwner { owner ->
                lateinit var engine: FakeLoopbackEngine
                val listener = openLoopback(owner, listOf("http://localhost:1455/callback")) { scope, url, receive ->
                    FakeLoopbackEngine(scope, url, receive).also { engine = it }
                }
                owner.coroutineContext[Job]!!.cancelAndJoin()
                assertEquals(1, engine.stops)
                assertFailsWith<CancellationException> { listener.awaitCallback() }
                assertFailsWith<IllegalStateException> { listener.armState("late") }
                assertFalse(engine.request("state=late&code=private-code"))
                listener.closeAndJoin()
                assertEquals(1, engine.stops)
            }
        }
    }

    test("concurrent and cancelled closes join engine cleanup and engine children exactly once") {
        runTest {
            withLoopbackOwner { owner ->
                val stopping = CompletableDeferred<Unit>()
                val allowStop = CompletableDeferred<Unit>()
                val childEnded = CompletableDeferred<Unit>()
                lateinit var engine: FakeLoopbackEngine
                val listener = openLoopback(owner, listOf("http://localhost:1455/callback")) { scope, url, receive ->
                    scope.launch {
                        try {
                            awaitCancellation()
                        } finally {
                            childEnded.complete(Unit)
                        }
                    }
                    FakeLoopbackEngine(scope, url, receive, stopBlock = {
                        stopping.complete(Unit)
                        allowStop.await()
                    }).also { engine = it }
                }
                runCurrent()
                val firstClose = launch { listener.closeAndJoin() }
                stopping.await()
                firstClose.cancel()
                val secondClose = launch { listener.closeAndJoin() }
                runCurrent()
                assertFalse(firstClose.isCompleted)
                assertFalse(secondClose.isCompleted)
                assertTrue(childEnded.isCompleted)
                allowStop.complete(Unit)
                firstClose.join()
                secondClose.join()
                listener.closeAndJoin()
                assertEquals(1, engine.stops)
                assertTrue(owner.isActive)
                assertTrue(owner.coroutineContext[Job]!!.children.none())
            }
        }
    }

    test("cleanup failure is sanitized repeatable and prevents fallback") {
        runTest {
            withLoopbackOwner { owner ->
                var attempts = 0
                val failure = assertFailsWith<IllegalStateException> {
                    openLoopback(
                        owner, listOf("http://localhost:1455/callback", "http://localhost:1457/callback"),
                    ) { scope, url, receive ->
                        attempts++
                        FakeLoopbackEngine(
                            scope, url, receive,
                            startBlock = { error("private-code") },
                            stopBlock = { error("state=private-state") },
                        )
                    }
                }
                assertEquals(1, attempts)
                assertFalse(failure.toString().contains("private"))
                assertFalse(failure.suppressedExceptions.single().toString().contains("private"))
                assertTrue(owner.isActive)
                lateinit var engine: FakeLoopbackEngine
                val listener = openLoopback(owner, listOf("http://localhost:1455/callback")) { scope, url, receive ->
                    FakeLoopbackEngine(scope, url, receive, stopBlock = { error("private-code") }).also { engine = it }
                }
                repeat(2) {
                    val closeFailure = assertFailsWith<IllegalStateException> { listener.closeAndJoin() }
                    assertFalse(closeFailure.toString().contains("private"))
                }
                assertEquals(1, engine.stops)
                assertTrue(owner.coroutineContext[Job]!!.children.none())
            }
        }
    }
}

private suspend fun withLoopbackOwner(block: suspend (CoroutineScope) -> Unit) {
    coroutineScope {
        val owner = SupervisorJob(coroutineContext[Job])
        try {
            block(CoroutineScope(coroutineContext + owner))
        } finally {
            withContext(NonCancellable) { owner.cancelAndJoin() }
        }
    }
}

private class FakeLoopbackEngine(
    val scope: CoroutineScope,
    val url: Url,
    private val receive: suspend (Parameters, String) -> Boolean,
    private val startBlock: suspend () -> Int = { url.port },
    private val stopBlock: suspend () -> Unit = {},
) : LoopbackEngine {
    var stops: Int = 0
        private set

    override suspend fun start(): Int = startBlock()

    override suspend fun stop() {
        stops++
        stopBlock()
    }

    suspend fun request(rawQuery: String): Boolean =
        receive(Url("http://localhost/callback?$rawQuery").parameters, rawQuery)
}
