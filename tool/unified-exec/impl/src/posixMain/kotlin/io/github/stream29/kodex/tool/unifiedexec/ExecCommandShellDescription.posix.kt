package io.github.stream29.kodex.tool.unifiedexec

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform

internal actual val execCommandHostPlatform: ExecCommandHostPlatform
    @OptIn(ExperimentalNativeApi::class)
    get() =
        if (Platform.osFamily == OsFamily.MACOSX) {
            ExecCommandHostPlatform.Macos
        } else {
            ExecCommandHostPlatform.Linux
        }
