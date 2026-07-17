package io.github.stream29.codex.lite.utils.shellclient

private val testShell: Shell
    get() = requireNotNull(Shell.resolve(ShellType.Sh))

internal actual val oneShotProcessCommand: TestShellCommand
    get() = TestShellCommand(command = "printf 'one-shot\\n'", shell = testShell)

internal actual val interactiveProcessCommand: TestShellCommand
    get() = TestShellCommand(
        command = $$"printf 'ready\\n'; IFS= read -r line; printf 'received:%s\\n' \"$line\"",
        shell = testShell,
    )

internal actual val delayedProcessCommand: TestShellCommand
    get() = TestShellCommand(command = "sleep 5", shell = testShell)

internal actual val ttyProbeProcessCommand: TestShellCommand
    get() = TestShellCommand(
        command = "[ -t 0 ] && [ -t 1 ] && printf 'tty=yes\\n' || printf 'tty=no\\n'",
        shell = testShell,
    )
