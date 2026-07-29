package io.github.stream29.codex.lite.tool.unifiedexec

import io.github.stream29.codex.lite.utils.shellclient.Shell

internal expect val unifiedExecTestShell: Shell

internal expect val interactiveExecCommand: String

internal expect val delayedExecCommand: String

internal expect val ttyProbeExecCommand: String
