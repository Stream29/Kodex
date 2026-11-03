package io.github.stream29.kodex.rpc.browser

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.backend.*
import kotlin.test.*

private const val testOrigin = "http://127.0.0.1:1234"
private const val testHostOrigin = "http://127.0.0.1:4321"
private val testCapabilities = FrontendHostCapabilities(
    FrontendCapabilityStatus.Ready, FrontendCapabilityStatus.Ready,
    FrontendCapabilityStatus.Ready, FrontendCapabilityStatus.Ready,
)

private fun descriptor(
    origin: String = testOrigin,
    baseUrl: String = testHostOrigin,
    backend: String = "backend",
    owner: String = "o".repeat(64),
    credential: String = "h".repeat(64),
    lease: Long = LocalFrontendHostOwnerLeaseMillis,
) = LocalFrontendHostDescriptor(
    LocalFrontendHostProtocol, "i".repeat(64), backend, origin, baseUrl,
    credential, LocalFrontendOwner(owner), "device", "/launch", lease, testCapabilities,
)

private fun bootstrap(
    host: LocalFrontendHostDescriptor = descriptor(),
    protocol: Int = LocalFrontendBootstrapProtocol,
) = LocalFrontendBootstrap(
    protocol, LocalBrowserEndpoint("backend", 1235, "b".repeat(64), BackendBusinessProtocol),
    "/project", host,
)

internal val frontendHostValidationTest by testSuite {
    test("bootstrap binds both instances and exact origins without interpreting backend paths") {
        validateFrontendBootstrap(bootstrap(), testOrigin, testHostOrigin)
        validateFrontendBootstrap(
            LocalFrontendBootstrap(
                LocalFrontendBootstrapProtocol, bootstrap().endpoint, "C:\\work\\project", descriptor(),
            ), testOrigin, testHostOrigin,
        )
    }
    test("bootstrap rejects legacy checkpoint rather than fabricating readiness") {
        assertFailsWith<IllegalArgumentException> {
            validateFrontendBootstrap(bootstrap(protocol = 1), testOrigin, testHostOrigin)
        }
    }
    test("bootstrap rejects cross origin and changed backend") {
        for (host in listOf(descriptor(origin = testHostOrigin), descriptor(backend = "other"))) {
            assertFailsWith<IllegalArgumentException> {
                validateFrontendBootstrap(bootstrap(host), testOrigin, testHostOrigin)
            }
        }
    }
    test("companion base cannot carry path query credentials or a remote address") {
        for (base in listOf(
            "$testHostOrigin/path", "$testHostOrigin?secret=value",
            "http://secret@127.0.0.1:4321", "http://example.com:4321",
        )) {
            assertFailsWith<IllegalArgumentException> {
                validateFrontendBootstrap(bootstrap(descriptor(baseUrl = base)), testOrigin, base)
            }
        }
    }
    test("host and business credentials must be independent and owner opaque") {
        for (host in listOf(descriptor(credential = "b".repeat(64)), descriptor(owner = "short"))) {
            assertFailsWith<IllegalArgumentException> {
                validateFrontendBootstrap(bootstrap(host), testOrigin, testHostOrigin)
            }
        }
    }
    test("host lease must agree with finite protocol budget") {
        for (lease in listOf(-1L, 0L, Long.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> {
                validateFrontendBootstrap(bootstrap(descriptor(lease = lease)), testOrigin, testHostOrigin)
            }
        }
    }
    test("valid response is correlated to exact host and opaque owner") {
        val host = descriptor()
        val reply = LocalFrontendHostReply.OwnerClosed
        assertSame(reply, validateHostResult(
            LocalFrontendHostResult(
                LocalFrontendHostProtocol, host.hostInstanceId, LocalFrontendOwner(host.owner.value), reply,
            ), host,
        ))
    }
    test("cross owner host or protocol replies cannot be consumed") {
        val host = descriptor()
        for (result in listOf(
            LocalFrontendHostResult(0, host.hostInstanceId, host.owner, LocalFrontendHostReply.OwnerClosed),
            LocalFrontendHostResult(1, "other", host.owner, LocalFrontendHostReply.OwnerClosed),
            LocalFrontendHostResult(1, host.hostInstanceId, LocalFrontendOwner("other"), LocalFrontendHostReply.OwnerClosed),
        )) {
            assertFailsWith<IllegalStateException> { validateHostResult(result, host) }
        }
    }
    test("pairing permits only explicitly scoped origin and bounded one time offer") {
        fun offer(origin: String = testOrigin, ttl: Long = 60_000) = LocalFrontendPairingOffer(
            LocalFrontendPairingProtocol, testHostOrigin, "i".repeat(64), "backend",
            origin, "/project", "t".repeat(64), ttl,
        )
        validatePairingOffer(offer(), testOrigin)
        for (invalid in listOf(offer(origin = testHostOrigin), offer(ttl = 0), offer(ttl = 60_001))) {
            assertFailsWith<IllegalArgumentException> { validatePairingOffer(invalid, testOrigin) }
        }
    }
}
