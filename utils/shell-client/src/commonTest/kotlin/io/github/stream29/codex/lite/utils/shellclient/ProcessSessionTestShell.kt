package io.github.stream29.codex.lite.utils.shellclient

internal data class TestShellCommand(
    val command: String,
    val shell: Shell,
)

internal expect val oneShotProcessCommand: TestShellCommand

internal expect val interactiveProcessCommand: TestShellCommand

internal expect val delayedProcessCommand: TestShellCommand

internal expect val ttyProbeProcessCommand: TestShellCommand
