package io.github.stream29.codex.lite.utils.shellclient

internal actual val shellHostPlatform: ShellHostPlatform
    get() =
        when {
            isWindowsRuntime() -> ShellHostPlatform.Windows
            System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> ShellHostPlatform.Macos
            else -> ShellHostPlatform.Linux
        }

internal fun Shell.invocation(command: String, login: Boolean): ShellInvocation =
    ShellInvocation(
        executable = path.toString(),
        argumentsBeforeCommand = type.argumentsBeforeCommand(login),
        command = command,
    )

private fun isWindowsRuntime(): Boolean =
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
