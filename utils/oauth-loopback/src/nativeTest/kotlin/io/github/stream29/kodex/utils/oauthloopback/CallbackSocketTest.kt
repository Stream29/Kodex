package io.github.stream29.kodex.utils.oauthloopback

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.ktor.http.Url
import kotlinx.coroutines.coroutineScope
import kotlin.test.assertEquals
import kotlin.test.assertTrue

val callbackSocketTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("explicit zero binds a real ephemeral socket and returns its selected port") {
        coroutineScope {
            val listener = openOAuthLoopbackListener(this, listOf("http://127.0.0.1:0/callback"))
            try {
                val bound = Url(listener.redirectUri)
                assertEquals("127.0.0.1", bound.host)
                assertEquals("/callback", bound.encodedPath)
                assertTrue(bound.specifiedPort in 1..65535)
                listener.armState("task-only-ephemeral-state")
            } finally {
                listener.closeAndJoin()
            }
        }
    }
}
