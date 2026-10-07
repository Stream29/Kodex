package io.github.stream29.kodex.tool.unifiedexec

internal enum class ExecCommandHostPlatform {
    Windows,
    Macos,
    Linux,
}

internal expect val execCommandHostPlatform: ExecCommandHostPlatform

internal const val ExecCommandShellDescription: String =
    "Shell binary to launch. Defaults to the user's default shell."

internal fun renderExecCommandYieldTimeDescription(platform: ExecCommandHostPlatform): String =
    if (platform == ExecCommandHostPlatform.Windows) {
        WindowsExecCommandYieldTimeDescription
    } else {
        DefaultExecCommandYieldTimeDescription
    }

internal fun renderExecCommandDescription(platform: ExecCommandHostPlatform): String =
    if (platform == ExecCommandHostPlatform.Windows) {
        "${UnifiedExecTools.ExecCommandDescription}\n\n$WindowsShellGuidance"
    } else {
        UnifiedExecTools.ExecCommandDescription
    }

private const val DefaultExecCommandYieldTimeDescription: String =
    "Wait before yielding output. Defaults to 10000 ms; effective range is 250-30000 ms."

private const val WindowsExecCommandYieldTimeDescription: String =
    "Maximum time to wait before returning a session ID for a still-running command. " +
        "Commands that finish sooner return immediately. For ordinary commands, omit this " +
        "parameter to use the 10000 ms default. Effective range on Windows is 10000-30000 ms."

private const val WindowsShellGuidance: String = """Windows safety rules:
- Do not compose destructive filesystem commands across shells. Do not enumerate paths in PowerShell and then pass them to `cmd /c`, batch builtins, or another shell for deletion or moving. Use one shell end-to-end, prefer native PowerShell cmdlets such as `Remove-Item` / `Move-Item` with `-LiteralPath`, and avoid string-built shell commands for file operations.
- Before any recursive delete or move on Windows, verify the resolved absolute target paths stay within the intended workspace or explicitly named target directory. Never issue a recursive delete or move against a computed path if the final target has not been checked.
- When using `Start-Process` to launch a background helper or service, pass `-WindowStyle Hidden` unless the user explicitly asked for a visible interactive window. Use visible windows only for interactive tools the user needs to see or control."""
