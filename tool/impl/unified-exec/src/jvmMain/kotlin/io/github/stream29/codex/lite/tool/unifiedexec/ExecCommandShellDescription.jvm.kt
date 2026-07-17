package io.github.stream29.codex.lite.tool.unifiedexec

internal actual val execCommandHostPlatform: ExecCommandHostPlatform
    get() =
        when {
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> ExecCommandHostPlatform.Windows
            System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> ExecCommandHostPlatform.Macos
            else -> ExecCommandHostPlatform.Linux
        }
