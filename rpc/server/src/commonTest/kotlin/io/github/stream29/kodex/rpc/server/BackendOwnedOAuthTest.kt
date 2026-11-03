@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.rpc.models.OAuthAuthorization
import io.github.stream29.kodex.rpc.models.OAuthTarget
import io.github.stream29.kodex.utils.oauthloopback.OAuthCallbackValuePolicy
import io.github.stream29.kodex.utils.oauthloopback.OAuthLoopbackListener
import io.ktor.http.URLBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val backendOwnedOAuthTest by testSuite(testConfig = TestConfig.testScope(isEnabled = false)) {
    test("a callback claimed before its HTTP response survives a racing pending release") { runTest {
        val fixture = OwnedOAuthFixture(backgroundScope)
        try {
            val authorization = fixture.oauth.start(OAuthTarget.Mcp("fake"))
            val response = fixture.listener.claimBeforeResponse(
                "${fixture.listener.redirectUri}?state=fixture-state&code=fixture-code",
            )
            fixture.oauth.abandonPending(authorization.attemptId)
            runCurrent()
            assertFalse(fixture.releaseIds.contains(41L))
            response()
            runCurrent()
            assertTrue(fixture.exchangeStarted.isCompleted)
            fixture.exchangeGate.complete(Unit)
            fixture.oauth.await(authorization.attemptId)
            assertEquals(listOf(41L), fixture.completedIds)
        } finally { fixture.oauth.closeAndJoin() }
    } }

    test("an accepted callback survives its deadline before the waiter resumes") { runTest {
        val fixture = OwnedOAuthFixture(backgroundScope)
        try {
            val authorization = fixture.oauth.start(OAuthTarget.Mcp("fake"))
            val resumeCallback = fixture.listener.claimBeforeResponse(
                "${fixture.listener.redirectUri}?state=fixture-state&code=fixture-code",
            )
            advanceTimeBy(2.seconds)
            runCurrent()
            assertFalse(fixture.releaseIds.contains(41L))
            resumeCallback()
            runCurrent()
            assertTrue(fixture.exchangeStarted.isCompleted)
            fixture.exchangeGate.complete(Unit)
            fixture.oauth.await(authorization.attemptId)
            assertEquals(listOf(41L), fixture.completedIds)
        } finally { fixture.oauth.closeAndJoin() }
    } }

    test("explicit cancellation after callback admission cancels the exact pending exchange") { runTest {
        val fixture = OwnedOAuthFixture(backgroundScope)
        try {
            val authorization = fixture.oauth.start(OAuthTarget.Mcp("fake"))
            val response = fixture.listener.claimBeforeResponse(
                "${fixture.listener.redirectUri}?state=fixture-state&code=fixture-code",
            )
            fixture.oauth.cancel(authorization.attemptId)
            runCurrent()
            response()
            runCurrent()
            assertEquals(listOf(41L), fixture.cancelIds)
            assertFalse(fixture.exchangeStarted.isCompleted)
            assertFailsWith<IllegalStateException> { fixture.oauth.await(authorization.attemptId) }
        } finally { fixture.oauth.closeAndJoin() }
    } }
    test("accepted exchange survives observer cancellation and pending-only release") { runTest {
        val fixture = OwnedOAuthFixture(backgroundScope)
        try {
            val authorization = fixture.oauth.start(OAuthTarget.Mcp("fake"))
            assertEquals(OAuthCallbackValuePolicy.RequireNonBlank, fixture.listener.policy)
            assertEquals("fixture-state", fixture.listener.state)
            val abandonedObserver = async { fixture.oauth.await(authorization.attemptId) }
            runCurrent()
            abandonedObserver.cancelAndJoin()
            fixture.listener.claim("${fixture.listener.redirectUri}?state=fixture-state&code=fixture-code")
            runCurrent()
            assertTrue(fixture.exchangeStarted.isCompleted)
            assertEquals(1, fixture.listener.closes)
            fixture.oauth.abandonPending(authorization.attemptId)
            assertFalse(fixture.releaseIds.contains(41L))
            val observer = async { fixture.oauth.await(authorization.attemptId) }
            fixture.exchangeGate.complete(Unit)
            runCurrent()
            observer.await()
            fixture.oauth.await(authorization.attemptId)
            assertEquals(listOf(41L), fixture.completedIds)
            assertEquals(1, fixture.listener.closes)
        } finally { fixture.oauth.closeAndJoin() }
    } }

    test("pending release closes the listener and does not submit a callback") { runTest {
        val fixture = OwnedOAuthFixture(backgroundScope)
        try {
            val authorization = fixture.oauth.start(OAuthTarget.Mcp("fake"))
            fixture.oauth.abandonPending(authorization.attemptId)
            runCurrent()
            assertEquals(listOf(41L), fixture.releaseIds)
            assertTrue(fixture.completedIds.isEmpty())
            assertEquals(1, fixture.listener.closes)
            assertFailsWith<IllegalStateException> { fixture.oauth.await(authorization.attemptId) }
        } finally { fixture.oauth.closeAndJoin() }
    } }

    test("explicit cancel may cancel an accepted exchange") { runTest {
        val fixture = OwnedOAuthFixture(backgroundScope)
        try {
            val authorization = fixture.oauth.start(OAuthTarget.Mcp("fake"))
            fixture.listener.claim("${fixture.listener.redirectUri}?state=fixture-state&code=fixture-code")
            runCurrent()
            assertTrue(fixture.exchangeStarted.isCompleted)
            fixture.oauth.cancel(authorization.attemptId)
            runCurrent()
            assertEquals(listOf(41L), fixture.cancelIds)
            assertTrue(fixture.completedIds.isEmpty())
            assertEquals(1, fixture.listener.closes)
            assertFailsWith<IllegalStateException> { fixture.oauth.await(authorization.attemptId) }
        } finally { fixture.oauth.closeAndJoin() }
    } }

    test("deadline includes preparation and releases an allocated inner attempt") { runTest {
        val fixture = OwnedOAuthFixture(backgroundScope, blockPreparation = true)
        try {
            val start = async { runCatching { fixture.oauth.start(OAuthTarget.Mcp("fake")) } }
            runCurrent()
            assertTrue(fixture.preparationStarted.isCompleted)
            advanceTimeBy(2.seconds)
            runCurrent()
            assertIs<IllegalStateException>(start.await().exceptionOrNull())
            assertEquals(listOf(41L), fixture.releaseIds)
            assertEquals(1, fixture.listener.closes)
        } finally { fixture.oauth.closeAndJoin() }
    } }

    test("invalid authorization binding fails safely and closes its listener") { runTest {
        val fixture = OwnedOAuthFixture(backgroundScope, invalidBinding = true)
        try {
            val failure = assertFailsWith<IllegalStateException> { fixture.oauth.start(OAuthTarget.Mcp("fake")) }
            assertFalse(failure.toString().contains("fixture-secret"))
            assertEquals(listOf(41L), fixture.releaseIds)
            assertEquals(1, fixture.listener.closes)
        } finally { fixture.oauth.closeAndJoin() }
    } }

    test("the callback deadline does not cancel accepted exchange or credential commit") { runTest {
        val fixture = OwnedOAuthFixture(backgroundScope)
        try {
            val authorization = fixture.oauth.start(OAuthTarget.Mcp("fake"))
            fixture.listener.claim("${fixture.listener.redirectUri}?state=fixture-state&code=fixture-code")
            runCurrent()
            val observer = async { fixture.oauth.await(authorization.attemptId) }
            advanceTimeBy(2.seconds)
            runCurrent()
            assertFalse(observer.isCompleted)
            fixture.exchangeGate.complete(Unit)
            runCurrent()
            observer.await()
            assertEquals(listOf(41L), fixture.completedIds)
        } finally { fixture.oauth.closeAndJoin() }
    } }

    test("backend shutdown releases pending listeners and wakes existing observers") { runTest {
        val fixture = OwnedOAuthFixture(backgroundScope)
        val authorization = fixture.oauth.start(OAuthTarget.Mcp("fake"))
        val observer = async { runCatching { fixture.oauth.await(authorization.attemptId) } }
        runCurrent()
        fixture.oauth.closeAndJoin()
        runCurrent()
        assertIs<IllegalStateException>(observer.await().exceptionOrNull())
        assertEquals(listOf(41L), fixture.releaseIds)
        assertEquals(1, fixture.listener.closes)
    } }

    test("a provider failure remains a sanitized retained outcome") { runTest {
        val fixture = OwnedOAuthFixture(backgroundScope, failExchange = true)
        try {
            val authorization = fixture.oauth.start(OAuthTarget.Mcp("fake"))
            fixture.listener.claim("${fixture.listener.redirectUri}?state=fixture-state&code=fixture-code")
            runCurrent()
            val first = assertFailsWith<IllegalStateException> { fixture.oauth.await(authorization.attemptId) }
            val second = assertFailsWith<IllegalStateException> { fixture.oauth.await(authorization.attemptId) }
            assertFalse(first.toString().contains("fixture-secret"))
            assertEquals(first.message, second.message)
            assertEquals(1, fixture.listener.closes)
        } finally { fixture.oauth.closeAndJoin() }
    } }
}

private class OwnedOAuthFixture(
    scope: CoroutineScope,
    private val blockPreparation: Boolean = false,
    private val invalidBinding: Boolean = false,
    private val failExchange: Boolean = false,
) {
    val listener = FakeOwnedListener()
    val preparationStarted = CompletableDeferred<Unit>()
    val exchangeStarted = CompletableDeferred<Unit>()
    val exchangeGate = CompletableDeferred<Unit>()
    val completedIds = mutableListOf<Long>()
    val cancelIds = mutableListOf<Long>()
    val releaseIds = mutableListOf<Long>()
    val oauth = BackendOwnedOAuth(
        scope = scope,
        candidates = { listOf(listener.redirectUri) },
        begin = { _, redirect, allocated ->
            allocated(41L)
            preparationStarted.complete(Unit)
            if (blockPreparation) awaitCancellation()
            val url = URLBuilder("https://example.invalid/authorize").apply {
                parameters.append("state", if (invalidBinding) "fixture-secret" else "fixture-state")
                parameters.append("redirect_uri", if (invalidBinding) "http://localhost:1/wrong" else redirect)
            }.buildString()
            BackendOAuthPreparation(41L, CompletableDeferred(OAuthAuthorization(41L, url)))
        },
        waitingState = { "fixture-state" },
        holdPendingDeadline = { true },
        complete = { id, _ ->
            exchangeStarted.complete(Unit)
            if (failExchange) error("fixture-secret")
            exchangeGate.await()
            completedIds += id
        },
        cancelInner = { cancelIds += it },
        releaseInner = { releaseIds += it },
        openListener = { _, _ -> listener },
        callbackDeadline = 1.seconds,
    )
}

private class FakeOwnedListener : OAuthLoopbackListener {
    override val redirectUri: String = "http://localhost:1455/auth/callback"
    val callback = CompletableDeferred<String>()
    var state: String? = null
    var policy: OAuthCallbackValuePolicy? = null
    var closes = 0
    private var admit: suspend () -> Boolean = { true }

    suspend fun claimBeforeResponse(value: String): () -> Unit {
        check(admit())
        return { callback.complete(value) }
    }

    suspend fun claim(value: String) { claimBeforeResponse(value)() }

    override suspend fun armState(state: String, valuePolicy: OAuthCallbackValuePolicy) =
        armStateWithAdmission(state, valuePolicy) { true }

    override suspend fun armStateWithAdmission(
        state: String, valuePolicy: OAuthCallbackValuePolicy, admit: suspend () -> Boolean,
    ) {
        this.state = state
        policy = valuePolicy
        this.admit = admit
    }

    override suspend fun awaitCallback(): String = callback.await()

    override suspend fun closeAndJoin() {
        closes++
        callback.cancel()
    }
}
