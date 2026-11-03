package io.github.stream29.kodex.app.webrenderersmoke

import io.github.stream29.kodex.cli.app.FrontendHost
import kotlinx.io.files.Path

/** Only the server-issued temporary cwd is supported; this is not a remote-directory/OAuth host. */
internal class FixtureHost(private val cwd: Path) : FrontendHost {
    override suspend fun resolveWorkingDirectory(directory: Path): Path {
        check(directory == cwd) { "Only the backend-issued fixture directory is allowed." }
        return cwd
    }
}
