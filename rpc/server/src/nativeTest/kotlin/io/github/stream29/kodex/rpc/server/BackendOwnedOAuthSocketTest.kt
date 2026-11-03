package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.rpc.models.OAuthAuthorization
import io.github.stream29.kodex.rpc.models.OAuthTarget
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val backendOwnedOAuthSocketTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("real loopback rejects malformed values and returns a response before commit") {
        withTimeout(15.seconds) {
            coroutineScope {
                val callbacks = mutableListOf<String>()
                val oauth = BackendOwnedOAuth(
                    scope = this,
                    candidates = { listOf("http://127.0.0.1:0/callback") },
                    begin = { _, redirect, allocated ->
                        allocated(41L)
                        val url = URLBuilder("https://example.invalid/authorize").apply {
                            parameters.append("state", "fixture-state")
                            parameters.append("redirect_uri", redirect)
                        }.buildString()
                        BackendOAuthPreparation(41L, CompletableDeferred(OAuthAuthorization(41L, url)))
                    },
                    waitingState = { "fixture-state" },
                    holdPendingDeadline = { true },
                    complete = { _, callback -> callbacks += callback },
                    cancelInner = {},
                    releaseInner = {},
                )
                val client = HttpClient(CIO) { expectSuccess = false }
                try {
                    val authorization = oauth.start(OAuthTarget.Mcp("fixture"))
                    val redirect = requireNotNull(Url(authorization.url).parameters["redirect_uri"])
                    val bound = Url(redirect)
                    assertEquals("127.0.0.1", bound.host)
                    assertTrue(bound.port in 1..65535)
                    assertEquals(HttpStatusCode.BadRequest, client.get("$redirect?state=wrong&code=fixture").status)
                    assertEquals(HttpStatusCode.BadRequest, client.get("$redirect?state=fixture-state&code=").status)
                    assertEquals(HttpStatusCode.BadRequest, client.get("$redirect?state=fixture-state&error=%20").status)
                    assertEquals(HttpStatusCode.OK, client.get("$redirect?state=fixture-state&code=fixture").status)
                    oauth.await(authorization.attemptId)
                    assertEquals(listOf("$redirect?state=fixture-state&code=fixture"), callbacks)
                } finally {
                    client.close()
                    oauth.closeAndJoin()
                }
            }
        }
    }
}
