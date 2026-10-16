package io.github.stream29.kodex.utils.shellclient

import kotlinx.io.files.Path

internal actual val shellHostPlatform: ShellHostPlatform = ShellHostPlatform.Windows

internal fun Shell.invocation(command: String, login: Boolean): ShellInvocation =
    ShellInvocation(
        executable = path.windowsPath(),
        argumentsBeforeCommand = type.argumentsBeforeCommand(login),
        command = command,
    )

internal fun Path.windowsPath(): String =
    toString().replace('/', '\\')
