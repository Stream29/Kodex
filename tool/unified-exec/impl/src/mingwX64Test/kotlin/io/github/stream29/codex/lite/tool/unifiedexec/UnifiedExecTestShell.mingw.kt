package io.github.stream29.codex.lite.tool.unifiedexec

import io.github.stream29.codex.lite.utils.shellclient.Shell
import io.github.stream29.codex.lite.utils.shellclient.ShellType

internal actual val unifiedExecTestShell: Shell
    get() = requireNotNull(Shell.resolve(ShellType.PowerShell))

internal actual val interactiveExecCommand: String =
    "Write-Output ready; \$line = [Console]::In.ReadLine(); Write-Output \"received:\$line\""

internal actual val delayedExecCommand: String = "Start-Sleep -Seconds 5"

internal actual val ttyProbeExecCommand: String =
    "if (-not [Console]::IsInputRedirected -and -not [Console]::IsOutputRedirected) { Write-Output tty=yes } else { Write-Output tty=no }"
