package io.github.stream29.codex.lite.tool.unifiedexec

import io.github.stream29.codex.lite.utils.shellclient.Shell
import io.github.stream29.codex.lite.utils.shellclient.ShellType

internal enum class ExecCommandHostPlatform {
    Windows,
    Macos,
    Linux,
}

internal expect val execCommandHostPlatform: ExecCommandHostPlatform

internal val execCommandShellDescription: String
    get() = renderExecCommandShellDescription(execCommandHostPlatform, Shell.default)

internal fun renderExecCommandShellDescription(
    platform: ExecCommandHostPlatform,
    defaultShell: Shell,
): String =
    when (platform) {
        ExecCommandHostPlatform.Windows ->
            "Optional shell executable path or command name. Omit it to use the dynamically resolved Windows default `${defaultShell.path}` (${defaultShell.type.displayName}). Commands without `shell` must use ${defaultShell.type.syntaxName}. Set `shell` only to intentionally change command language: use `pwsh` or `powershell` for PowerShell, `cmd` for Command Prompt, or an installed POSIX shell such as `bash`."

        ExecCommandHostPlatform.Macos ->
            "Optional shell executable path or command name. Omit it to use the dynamically resolved macOS default `${defaultShell.path}` (${defaultShell.type.displayName}). Commands without `shell` must use ${defaultShell.type.syntaxName}. Set `shell` only to intentionally change command language; recognized shell names are `sh`, `bash`, `zsh`, `pwsh`/`powershell`, and `cmd`."

        ExecCommandHostPlatform.Linux ->
            "Optional shell executable path or command name. Omit it to use the dynamically resolved Linux default `${defaultShell.path}` (${defaultShell.type.displayName}). Commands without `shell` must use ${defaultShell.type.syntaxName}. Set `shell` only to intentionally change command language; recognized shell names are `sh`, `bash`, `zsh`, `pwsh`/`powershell`, and `cmd`."
    }

private val ShellType.displayName: String
    get() =
        when (this) {
            ShellType.Sh -> "POSIX sh"
            ShellType.Bash -> "Bash"
            ShellType.Zsh -> "Zsh"
            ShellType.PowerShell -> "PowerShell"
            ShellType.Cmd -> "Command Prompt"
        }

private val ShellType.syntaxName: String
    get() =
        when (this) {
            ShellType.Sh, ShellType.Bash, ShellType.Zsh -> "POSIX shell syntax"
            ShellType.PowerShell -> "PowerShell syntax"
            ShellType.Cmd -> "cmd.exe syntax"
        }
