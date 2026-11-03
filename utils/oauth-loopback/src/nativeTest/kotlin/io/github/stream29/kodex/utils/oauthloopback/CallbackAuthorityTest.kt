package io.github.stream29.kodex.utils.oauthloopback

import de.infix.testBalloon.framework.core.testSuite
import io.ktor.http.Url
import kotlin.test.*

internal val callbackAuthorityTest by testSuite {
    test("callback authority matches reserved host actual port and exact encoded path") {
        val url = Url("http://localhost:1455/auth/callback")
        assertTrue(acceptsCallbackAuthority(url, 1455, listOf("localhost:1455"), "/auth/callback"))
        for (hosts in listOf(
            null, emptyList(), listOf("localhost:1455", "localhost:1455"),
            listOf("localhost:1457"), listOf("127.0.0.1:1455"),
            listOf("example.invalid:1455"), listOf("user@localhost:1455"),
        )) {
            assertFalse(acceptsCallbackAuthority(url, 1455, hosts, "/auth/callback"))
        }
        for (path in listOf("/auth/callback/", "/%61uth/callback", "//auth/callback", "/other")) {
            assertFalse(acceptsCallbackAuthority(url, 1455, listOf("localhost:1455"), path))
        }
    }
    test("ephemeral listeners use actual port and standard HTTP port may omit its suffix") {
        assertTrue(acceptsCallbackAuthority(
            Url("http://127.0.0.1:0/callback"), 12345, listOf("127.0.0.1:12345"), "/callback",
        ))
        assertFalse(acceptsCallbackAuthority(
            Url("http://127.0.0.1:0/callback"), 12345, listOf("127.0.0.1:0"), "/callback",
        ))
        assertTrue(acceptsCallbackAuthority(
            Url("http://localhost/callback"), 80, listOf("localhost"), "/callback",
        ))
    }
}
