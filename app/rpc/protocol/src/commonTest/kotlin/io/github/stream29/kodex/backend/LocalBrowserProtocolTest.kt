package io.github.stream29.kodex.backend

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.*

val localBrowserProtocolTest by testSuite {
    test("frontend bootstrap and admission DTOs preserve versions and hide secrets in string conversion") {
        val credential = "d".repeat(64)
        val grant = LocalBrowserGrant(LocalBrowserGrantProtocol, "http://127.0.0.1:18801", credential, "/project")
        assertFalse(grant.toString().contains(credential))
        val bootstrap = LocalFrontendBootstrap(LocalBrowserGrantProtocol,
            LocalBrowserEndpoint("instance", 18802, credential, BackendBusinessProtocol), "/canonical/project")
        val decoded = Json.decodeFromString(LocalFrontendBootstrap.serializer(),
            Json.encodeToString(LocalFrontendBootstrap.serializer(), bootstrap))
        assertEquals(18802, decoded.endpoint.port)
        assertEquals(credential, decoded.endpoint.password)
        assertEquals(BackendBusinessProtocol, decoded.endpoint.businessProtocol)
        assertEquals("/canonical/project", decoded.workingDirectory)
        assertFalse(decoded.toString().contains(credential))
    }
    test("origins are exact canonical loopback HTTP authorities") {
        assertEquals("http://127.0.0.1:8080", canonicalLocalBrowserOrigin("http://127.0.0.1:8080"))
        assertEquals("http://localhost", canonicalLocalBrowserOrigin("http://localhost:80"))
        assertEquals("http://localhost", canonicalLocalBrowserOrigin("http://localhost"))
        for (origin in listOf(
            "null", "https://localhost:8080", "http://example.test:8080",
            "http://127.0.0.1.evil.test:8080", "http://user@localhost:8080",
            "http://localhost:8080/", "http://localhost:8080/path", "http://localhost:8080?q",
            "http://localhost:8080#fragment", "http://localhost:0", "http://localhost:65536",
            "http://localhost:080", "http://localhost:8080,http://evil.test",
        )) assertFailsWith<IllegalArgumentException> { canonicalLocalBrowserOrigin(origin) }
    }
    test("hello uses explicit versions and does not expose credentials through string conversion") {
        val secret = "private-test-credential-never-log-this"
        val hello = LocalBrowserHello(LocalBrowserProtocol, "instance", secret, BackendBusinessProtocol, null)
        val decoded = Json.decodeFromString(
            LocalBrowserHello.serializer(), Json.encodeToString(LocalBrowserHello.serializer(), hello),
        )
        assertEquals(secret, decoded.password)
        assertEquals(LocalBrowserProtocol, decoded.protocol)
        assertNull(decoded.workingDirectory)
        assertFalse(hello.toString().contains(secret))
        assertFailsWith<SerializationException> {
            Json.decodeFromString(LocalBrowserHello.serializer(),
                """{"instanceId":"instance","password":"$secret","businessProtocol":"0.5.2","workingDirectory":null}""")
        }
    }
    test("the existing Native record can be read without adopting its filesystem or process fields") {
        val json = Json { ignoreUnknownKeys = true }
        val record = """{"instanceId":"instance","pid":12,"port":12345,"password":"${"x".repeat(64)}","applicationVersion":"future","businessProtocol":"$BackendBusinessProtocol","controlProtocol":$BackendControlProtocol}"""
        val endpoint = json.decodeFromString(LocalBrowserEndpoint.serializer(), record)
        assertEquals(12345, endpoint.port)
        assertEquals("instance", endpoint.instanceId)
        assertEquals(BackendBusinessProtocol, endpoint.businessProtocol)
        assertFalse(endpoint.toString().contains(endpoint.password))
    }
}
