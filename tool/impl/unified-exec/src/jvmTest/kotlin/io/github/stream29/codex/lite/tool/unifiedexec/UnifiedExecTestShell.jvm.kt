package io.github.stream29.codex.lite.tool.unifiedexec

import io.github.stream29.codex.lite.utils.shellclient.Shell
import io.github.stream29.codex.lite.utils.shellclient.ShellType

private val isWindows: Boolean =
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

internal actual val unifiedExecTestShell: Shell
    get() = requireNotNull(
        Shell.resolve(if (isWindows) ShellType.PowerShell else ShellType.Sh),
    )

internal actual val interactiveExecCommand: String
    get() = if (isWindows) {
        PowerShellInteractiveExecCommand
    } else {
        "printf 'ready\\n'; IFS= read -r line; printf 'received:%s\\n' \"\$line\""
    }

internal actual val delayedExecCommand: String
    get() = if (isWindows) "Start-Sleep -Seconds 5" else "sleep 5"

internal actual val ttyProbeExecCommand: String
    get() = if (isWindows) PowerShellTtyProbeExecCommand else PosixTtyProbeExecCommand

private const val PosixTtyProbeExecCommand: String =
    "[ -t 0 ] && [ -t 1 ] && printf 'tty=yes\\n' || printf 'tty=no\\n'"

private const val PowerShellInteractiveExecCommand: String =
    "Write-Output ready; \$line = [Console]::In.ReadLine(); Write-Output \"received:\$line\""

private const val PowerShellTtyProbeExecCommand: String =
    "if (-not [Console]::IsInputRedirected -and -not [Console]::IsOutputRedirected) { Write-Output tty=yes } else { Write-Output tty=no }"
