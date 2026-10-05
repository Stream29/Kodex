package io.github.stream29.kodex.utils.shellclient

internal fun ShellType.argumentsBeforeCommand(login: Boolean): List<String> =
    when (this) {
        ShellType.Sh, ShellType.Bash, ShellType.Zsh -> listOf(if (login) "-lc" else "-c")
        ShellType.PowerShell -> buildList {
            if (!login) add("-NoProfile")
            add("-Command")
        }

        ShellType.Cmd -> listOf("/d", "/s", "/c")
    }

internal enum class ShellHostPlatform {
    Windows,
    Macos,
    Linux,
}

internal expect val shellHostPlatform: ShellHostPlatform

internal data class ShellInvocation(
    val executable: String,
    val argumentsBeforeCommand: List<String>,
    val command: String,
)

/** Initial terminal width used by backends that explicitly set a terminal size. */
internal const val DefaultPtyColumns: Int = 80

/** Initial terminal height used by backends that explicitly set a terminal size. */
internal const val DefaultPtyRows: Int = 24
