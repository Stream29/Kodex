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

internal actual val separatedOutputProcessCommand: TestShellCommand
    get() = TestShellCommand(
        command = "printf 'stdout-only\\n'; printf 'stderr-only\\n' >&2",
        shell = testShell,
    )

internal actual val environmentProbeProcessCommands: List<TestShellCommand>
    get() = listOf(
        TestShellCommand(command = "printf '%s' \"\$CODEXLITE_SHELL_TEST\"", shell = testShell),
    )

internal actual fun unicodeProbeProcessCommand(
    markerFileName: String,
    content: String,
): TestShellCommand =
    TestShellCommand(
        command = "printf '%s' '$content' > '$markerFileName'; printf '%s' '$content'",
        shell = testShell,
    )
