package io.github.stream29.codex.lite.tool.unifiedexec

import io.github.stream29.codex.lite.utils.shellclient.Shell
import io.github.stream29.codex.lite.utils.shellclient.ShellType

internal actual val unifiedExecTestShell: Shell
    get() = requireNotNull(Shell.resolve(ShellType.Sh))

internal actual val interactiveExecCommand: String =
    "printf 'ready\\n'; IFS= read -r line; printf 'received:%s\\n' \"\$line\""

internal actual val delayedExecCommand: String = "sleep 5"

internal actual val ttyProbeExecCommand: String =
    "[ -t 0 ] && [ -t 1 ] && printf 'tty=yes\\n' || printf 'tty=no\\n'"
