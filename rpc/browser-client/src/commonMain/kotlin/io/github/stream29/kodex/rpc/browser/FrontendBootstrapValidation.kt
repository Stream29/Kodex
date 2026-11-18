package io.github.stream29.kodex.rpc.browser

import io.github.stream29.kodex.backend.LocalFrontendBootstrap
import io.github.stream29.kodex.backend.LocalFrontendBootstrapProtocol
import io.github.stream29.kodex.backend.canonicalLocalBrowserOrigin

internal fun validateLocalWebLocation(origin: String, path: String, ticket: String?) {
    require(origin == canonicalLocalBrowserOrigin(origin) && origin.startsWith("http://127.0.0.1:")) {
        "Invalid local Web origin."
    }
    require(path.matches(Regex("/web/[0-9a-f]{64}/"))) { "Invalid local Web context." }
    require(ticket == null || ticket.matches(Regex("[0-9a-f]{64}"))) { "Invalid frontend capability." }
}

private val WindowsAbsolutePath = Regex("^[A-Za-z]:[/\\\\]")

internal fun validateFrontendBootstrap(info: LocalFrontendBootstrap) {
    require(info.protocol == LocalFrontendBootstrapProtocol) { "Frontend bootstrap is incompatible." }
    val directory = info.workingDirectory
    require('\u0000' !in directory &&
        (directory.startsWith("/") || directory.startsWith("\\\\") || WindowsAbsolutePath.containsMatchIn(directory))
    ) { "Invalid backend working directory." }
    require(info.endpoint.password.matches(Regex("[0-9a-f]{64}"))) { "Invalid scoped backend credential." }
    validateBrowserEndpoint(info.endpoint)
}
