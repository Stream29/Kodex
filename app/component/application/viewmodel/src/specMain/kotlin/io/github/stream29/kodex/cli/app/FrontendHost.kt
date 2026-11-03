package io.github.stream29.kodex.cli.app

import kotlinx.io.files.Path

/** Borrowed frontend capabilities; a browser host must not impersonate Native filesystem or shell access. */
public interface FrontendHost {
    /** Resolves in the selected execution environment, not implicitly in the browser's device. */
    public suspend fun resolveWorkingDirectory(directory: Path): Path
}
