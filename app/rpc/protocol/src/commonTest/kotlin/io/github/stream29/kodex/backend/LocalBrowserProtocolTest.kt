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
        val bootstrap = LocalFrontendBootstrap(LocalFrontendBootstrapProtocol,
            LocalBrowserEndpoint("instance", 18802, credential, BackendBusinessProtocol), "/canonical/project",
            testHostDescriptor(credential))
        val decoded = Json.decodeFromString(LocalFrontendBootstrap.serializer(),
            Json.encodeToString(LocalFrontendBootstrap.serializer(), bootstrap))
        assertEquals(18802, decoded.endpoint.port)
        assertEquals(credential, decoded.endpoint.password)
        assertEquals(BackendBusinessProtocol, decoded.endpoint.businessProtocol)
        assertEquals("/canonical/project", decoded.workingDirectory)
        assertEquals(LocalFrontendBootstrapProtocol, decoded.protocol)
        assertEquals(LocalFrontendHostProtocol, decoded.host.protocol)
        assertEquals("instance", decoded.host.backendInstanceId)
        assertEquals("owner-test", decoded.host.owner.value)
        assertFalse(decoded.toString().contains(credential))
        assertFalse(decoded.host.toString().contains(credential))
        assertFalse(decoded.host.owner.toString().contains("owner-test"))
        assertFailsWith<SerializationException> {
            Json.decodeFromString<LocalFrontendBootstrap>(
                """{"protocol":1,"endpoint":{"instanceId":"instance","port":18802,"password":"$credential","businessProtocol":"$BackendBusinessProtocol"},"workingDirectory":"/project"}""",
            )
        }
    }
    test("business bootstrap and companion checkpoints are independent of control and browser grant") {
        assertEquals("0.5.4", BackendBusinessProtocol)
        assertEquals(1, BackendControlProtocol)
        assertEquals(1, LocalBrowserProtocol)
        assertEquals(1, LocalBrowserGrantProtocol)
        assertEquals(2, LocalFrontendBootstrapProtocol)
        assertEquals(1, LocalFrontendHostProtocol)
        assertEquals(1, LocalFrontendPairingProtocol)
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

private fun testHostDescriptor(credential: String): LocalFrontendHostDescriptor = LocalFrontendHostDescriptor(
    protocol = LocalFrontendHostProtocol,
    hostInstanceId = "host-test",
    backendInstanceId = "instance",
    origin = "http://127.0.0.1:18801",
    baseUrl = "http://127.0.0.1:18801",
    credential = credential,
    owner = LocalFrontendOwner("owner-test"),
    executionEnvironmentId = "device-test",
    launchDirectory = "/canonical/project",
    leaseMillis = LocalFrontendHostOwnerLeaseMillis,
    capabilities = FrontendHostCapabilities(
        FrontendCapabilityStatus.Negotiating, FrontendCapabilityStatus.Negotiating,
        FrontendCapabilityStatus.Negotiating, FrontendCapabilityStatus.Negotiating,
    ),
)
