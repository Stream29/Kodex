package io.github.stream29.codex.lite.utils.codexlitehome

import io.github.stream29.codex.lite.utils.osenvironment.requireUserHomeDirectory
import kotlinx.io.files.Path

/** Process-wide root for Codex Lite-owned settings, sessions, logs, and artifacts. */
public val CodexLiteHome: Path = Path(requireUserHomeDirectory(), ".codexlite")
