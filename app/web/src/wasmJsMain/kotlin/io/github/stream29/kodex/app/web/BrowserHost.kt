package io.github.stream29.kodex.app.web

import io.github.stream29.kodex.cli.app.FrontendHost
import io.github.stream29.kodex.rpc.browser.FrontendHostConnection
import kotlinx.io.files.Path

internal class BrowserHost(
    private val cwd: Path,
    val frontendServer: FrontendHostConnection,
) : FrontendHost {
    override suspend fun resolveWorkingDirectory(directory: Path): Path {
        require(directory == cwd) { "This browser connection is bound to its backend-confirmed initial directory." }
        return cwd
    }
    /** A backend-owned callback has no frontend reservation; the dialog presents its exact URL. */
    suspend fun openAuthorization(url: String): Boolean = false
}
