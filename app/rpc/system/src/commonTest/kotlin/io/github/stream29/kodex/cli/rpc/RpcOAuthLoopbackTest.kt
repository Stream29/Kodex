package io.github.stream29.kodex.cli.rpc

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

val rpcOAuthLoopbackTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("actual loopback listener rejects wrong or repeated state and does not echo secrets") {
        coroutineScope {
            val listener = startLoopback(this, listOf("http://127.0.0.1/callback"), testPort = 0)
            val http = HttpClient(CIO)
            try {
                listener.expectState("expected")
                val wait = async { listener.awaitCallback() }
                val invalid = http.get("${listener.redirectUri}?code=private-code&state=wrong")
                assertEquals(HttpStatusCode.BadRequest, invalid.status)
                assertFalse(invalid.bodyAsText().contains("private-code"))
                assertFalse(wait.isCompleted)
                val duplicate = http.get("${listener.redirectUri}?code=private-code&state=expected&state=expected")
                assertEquals(HttpStatusCode.BadRequest, duplicate.status)
                assertFalse(wait.isCompleted)
                val valid = http.get("${listener.redirectUri}?code=private-code&state=expected")
                assertEquals(HttpStatusCode.OK, valid.status)
                assertFalse(valid.bodyAsText().contains("private-code"))
                assertEquals("${listener.redirectUri}?code=private-code&state=expected", wait.await())
            } finally {
                http.close()
                withContext(NonCancellable) { listener.close() }
            }
        }
    }
    test("an occupied listener address fails locally without cancelling its frontend owner") {
        coroutineScope {
            val listener = startLoopback(this, listOf("http://127.0.0.1/callback"), testPort = 0)
            try {
                assertFailsWith<IllegalStateException> { startLoopback(this, listOf(listener.redirectUri)) }
                ensureActive()
                listener.expectState("still-live")
            } finally { withContext(NonCancellable) { listener.close() } }
        }
    }
}
