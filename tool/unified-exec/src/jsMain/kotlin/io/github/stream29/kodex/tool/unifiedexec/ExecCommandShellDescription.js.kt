package io.github.stream29.kodex.tool.unifiedexec

import node.os.platform

internal actual val execCommandHostPlatform: ExecCommandHostPlatform
    get() =
        when (platform().toString()) {
            "win32" -> ExecCommandHostPlatform.Windows
            "darwin" -> ExecCommandHostPlatform.Macos
            else -> ExecCommandHostPlatform.Linux
        }
