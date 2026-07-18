package io.github.stream29.codex.lite.utils.shellclient

import node.os.platform

private val isWindows: Boolean
    get() = platform().toString() == "win32"

private val testShell: Shell
    get() = requireNotNull(
        Shell.resolve(if (isWindows) ShellType.PowerShell else ShellType.Sh),
    )

internal actual val oneShotProcessCommand: TestShellCommand
    get() = TestShellCommand(
        command = if (isWindows) "Write-Output one-shot" else "printf 'one-shot\\n'",
        shell = testShell,
    )

internal actual val interactiveProcessCommand: TestShellCommand
    get() = TestShellCommand(
        command = if (isWindows) {
            PowerShellInteractiveProcessCommand
        } else {
            $$"printf 'ready\\n'; IFS= read -r line; printf 'received:%s\\n' \"$line\""
        },
        shell = testShell,
    )

internal actual val delayedProcessCommand: TestShellCommand
    get() = TestShellCommand(
        command = if (isWindows) "Start-Sleep -Seconds 5" else "sleep 5",
        shell = testShell,
    )

internal actual val ttyProbeProcessCommand: TestShellCommand
    get() = TestShellCommand(
        command = if (isWindows) PowerShellTtyProbeProcessCommand else PosixTtyProbeProcessCommand,
        shell = testShell,
    )

internal actual fun unicodeProbeProcessCommand(
    markerFileName: String,
    content: String,
): TestShellCommand =
    TestShellCommand(
        command = if (isWindows) {
            "\$bytes = [System.Text.Encoding]::UTF8.GetBytes('$content'); " +
                "[System.IO.File]::WriteAllBytes('$markerFileName', \$bytes); " +
                "\$stdout = [System.Console]::OpenStandardOutput(); " +
                "\$stdout.Write(\$bytes, 0, \$bytes.Length)"
        } else {
            "printf '%s' '$content' > '$markerFileName'; printf '%s' '$content'"
        },
        shell = testShell,
    )

private const val PosixTtyProbeProcessCommand: String =
    "[ -t 0 ] && [ -t 1 ] && printf 'tty=yes\\n' || printf 'tty=no\\n'"

private const val PowerShellInteractiveProcessCommand: String =
    "Write-Output ready; \$line = [Console]::In.ReadLine(); Write-Output \"received:\$line\""

private const val PowerShellTtyProbeProcessCommand: String =
    "if (-not [Console]::IsInputRedirected -and -not [Console]::IsOutputRedirected) { Write-Output tty=yes } else { Write-Output tty=no }"
