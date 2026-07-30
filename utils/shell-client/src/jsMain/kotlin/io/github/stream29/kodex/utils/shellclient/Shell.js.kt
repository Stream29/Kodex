package io.github.stream29.kodex.utils.shellclient

import node.os.platform

internal actual val shellHostPlatform: ShellHostPlatform
    get() =
        when (platform().toString()) {
            "win32" -> ShellHostPlatform.Windows
            "darwin" -> ShellHostPlatform.Macos
            else -> ShellHostPlatform.Linux
        }

internal fun Shell.invocation(command: String, login: Boolean): ShellInvocation =
    ShellInvocation(
        executable = path.toString(),
        argumentsBeforeCommand = type.argumentsBeforeCommand(login),
        command = command,
    )
