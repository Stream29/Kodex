package io.github.stream29.kodex.utils.kodexhome

import kotlinx.io.files.Path

/**
 * Supplies the root directory owned by Kodex.
 *
 * Implementations choose how the host home directory is discovered. Callers
 * depend only on the stable path contract and do not need to know that policy.
 */
public interface KodexHomeProvider {
    /** Root for Kodex-owned settings, sessions, logs, and artifacts. */
    public val path: Path
}
