package io.github.stream29.kodex.utils.kodexhome

import io.github.stream29.kodex.utils.osenvironment.requireUserHomeDirectory
import kotlinx.io.files.Path

/** Process-wide root for Kodex-owned settings, sessions, logs, and artifacts. */
public object DefaultKodexHomeProvider : KodexHomeProvider {
    override val path: Path = Path(requireUserHomeDirectory(), ".kodex")
}

/** Compatibility projection for callers that only need the process-wide path. */
public val KodexHome: Path
    get() = DefaultKodexHomeProvider.path
