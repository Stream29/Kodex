package io.github.stream29.kodex.app.web

import io.github.stream29.kodex.rpc.browser.FrontendHostConnection

internal class BrowserHost(
    val frontendServer: FrontendHostConnection,
) {
    /** A backend-owned callback has no frontend reservation; the dialog presents its exact URL. */
    suspend fun openAuthorization(url: String): Boolean = false
}
