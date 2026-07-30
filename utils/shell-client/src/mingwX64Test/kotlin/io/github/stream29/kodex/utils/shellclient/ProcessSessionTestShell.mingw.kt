package io.github.stream29.kodex.utils.shellclient

private val testShell: Shell
    get() = requireNotNull(Shell.resolve(ShellType.PowerShell))

internal actual val oneShotProcessCommand: TestShellCommand
    get() = TestShellCommand(command = "Write-Output one-shot", shell = testShell)

internal actual val interactiveProcessCommand: TestShellCommand
    get() = TestShellCommand(command = PowerShellInteractiveProcessCommand, shell = testShell)

internal actual val delayedProcessCommand: TestShellCommand
    get() = TestShellCommand(command = "Start-Sleep -Seconds 5", shell = testShell)

internal actual val ttyProbeProcessCommand: TestShellCommand
    get() = TestShellCommand(command = PowerShellTtyProbeProcessCommand, shell = testShell)

internal actual val separatedOutputProcessCommand: TestShellCommand
    get() = TestShellCommand(
        command = "Write-Output stdout-only; [Console]::Error.WriteLine('stderr-only')",
        shell = testShell,
    )

internal actual val environmentProbeProcessCommands: List<TestShellCommand>
    get() = listOf(
        TestShellCommand(command = "Write-Output \$env:KODEX_SHELL_TEST", shell = testShell),
        TestShellCommand(
            command = "set KODEX_SHELL_TEST",
            shell = requireNotNull(Shell.resolve(ShellType.Cmd)),
        ),
    )

internal actual fun unicodeProbeProcessCommand(
    markerFileName: String,
    content: String,
): TestShellCommand =
    TestShellCommand(
        command = "if (-not [System.Console]::IsOutputRedirected) { " +
            "[System.Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(\$false) }; " +
            "\$bytes = [System.Text.Encoding]::UTF8.GetBytes('$content'); " +
            "[System.IO.File]::WriteAllBytes('$markerFileName', \$bytes); " +
            "\$stdout = [System.Console]::OpenStandardOutput(); " +
            "\$stdout.Write(\$bytes, 0, \$bytes.Length)",
        shell = testShell,
    )

private const val PowerShellInteractiveProcessCommand: String =
    "Write-Output ready; \$line = [Console]::In.ReadLine(); Write-Output \"received:\$line\""

private const val PowerShellTtyProbeProcessCommand: String =
    "if (-not [Console]::IsInputRedirected -and -not [Console]::IsOutputRedirected) { Write-Output tty=yes } else { Write-Output tty=no }"
