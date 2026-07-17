package io.github.stream29.codex.lite.utils.shellclient

internal actual val shellHostPlatform: ShellHostPlatform = ShellHostPlatform.Windows

internal fun Shell.invocation(command: String, login: Boolean): ShellInvocation =
    ShellInvocation(
        executable = path.toString(),
        argumentsBeforeCommand = type.argumentsBeforeCommand(login),
        command = command,
    )
