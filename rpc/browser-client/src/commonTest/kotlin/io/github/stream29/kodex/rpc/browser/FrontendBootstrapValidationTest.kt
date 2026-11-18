package io.github.stream29.kodex.rpc.browser

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.backend.*
import kotlin.test.assertFailsWith

private fun bootstrap(
    protocol: Int = LocalFrontendBootstrapProtocol,
    directory: String = "/project",
    instance: String = "backend",
    port: Int = 1235,
    password: String = "b".repeat(64),
    business: String = BackendBusinessProtocol,
) = LocalFrontendBootstrap(protocol, LocalBrowserEndpoint(instance, port, password, business), directory)

internal val frontendBootstrapValidationTest by testSuite {
    test("bootstrap accepts canonical directory syntax on Unix and Windows") {
        for (path in listOf("/workspace/目录", "C:\\work\\project", "\\\\server\\project")) {
            validateFrontendBootstrap(bootstrap(directory = path))
        }
    }
    test("bootstrap rejects old protocol, incompatible business transport, bad directories and credentials") {
        for (invalid in listOf(
            bootstrap(protocol = 2), bootstrap(directory = "relative"),
            bootstrap(directory = "/project\u0000"), bootstrap(instance = ""), bootstrap(port = 0),
            bootstrap(password = "non-scoped".repeat(8)), bootstrap(business = "old"),
        )) {
            assertFailsWith<IllegalArgumentException> { validateFrontendBootstrap(invalid) }
        }
    }
    test("launch location requires a loopback listener, exact scoped path and optional hex ticket") {
        val origin = "http://127.0.0.1:1234"
        val path = "/web/${"a".repeat(64)}/"
        validateLocalWebLocation(origin, path, "b".repeat(64))
        for (invalid in listOf("http://localhost:1234", "https://127.0.0.1:1234", "http://remote:1234")) {
            assertFailsWith<IllegalArgumentException> { validateLocalWebLocation(invalid, path, null) }
        }
        for (invalid in listOf("/web/a/", "$path../", "$path?x=1", "/web/${"A".repeat(64)}/")) {
            assertFailsWith<IllegalArgumentException> { validateLocalWebLocation(origin, invalid, null) }
        }
        assertFailsWith<IllegalArgumentException> { validateLocalWebLocation(origin, path, "short") }
    }
}
