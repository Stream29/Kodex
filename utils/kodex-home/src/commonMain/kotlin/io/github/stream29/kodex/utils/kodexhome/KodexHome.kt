package io.github.stream29.kodex.utils.kodexhome

import io.github.stream29.kodex.utils.osenvironment.requireUserHomeDirectory
import kotlinx.io.files.Path

/** Process-wide root for Kodex-owned settings, sessions, logs, and artifacts. */
public val KodexHome: Path = Path(requireUserHomeDirectory(), ".kodex")
