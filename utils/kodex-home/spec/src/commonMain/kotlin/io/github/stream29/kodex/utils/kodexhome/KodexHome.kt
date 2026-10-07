package io.github.stream29.kodex.utils.kodexhome

import io.github.stream29.kodex.utils.osenvironment.requireUserHomeDirectory
import kotlinx.io.files.Path

/**
 * Process-wide default root for Kodex-owned settings, sessions, logs and artifacts.
 *
 * This is a host path query, not an acquired Home resource or a lease owner.
 * Explicit Home paths passed to startup are independent of this default.
 *
 * @throws IllegalStateException if the host cannot determine its user home directory.
 */
public val KodexHome: Path = Path(requireUserHomeDirectory(), ".kodex")
