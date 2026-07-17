package io.github.stream29.codex.lite.utils.shellclient

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

private const val PowerShellInteractiveProcessCommand: String =
    "Write-Output ready; \$line = [Console]::In.ReadLine(); Write-Output \"received:\$line\""

private const val PowerShellTtyProbeProcessCommand: String =
    "if (-not [Console]::IsInputRedirected -and -not [Console]::IsOutputRedirected) { Write-Output tty=yes } else { Write-Output tty=no }"
