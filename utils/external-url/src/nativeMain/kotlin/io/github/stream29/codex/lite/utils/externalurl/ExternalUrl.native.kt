package io.github.stream29.codex.lite.utils.externalurl

import io.github.stream29.codex.lite.utils.processclient.ProcessCommand
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform

@OptIn(ExperimentalNativeApi::class)
internal actual suspend fun openExternalUrlOnHost(url: String): OpenExternalUrlResult =
    when (Platform.osFamily) {
        OsFamily.MACOSX -> openExternalUrlWithProcess(
            ProcessCommand(
                executable = "open",
                arguments = listOf(url),
            ),
        )

        OsFamily.LINUX -> openExternalUrlWithProcess(
            ProcessCommand(
                executable = "xdg-open",
                arguments = listOf(url),
            ),
        )

        OsFamily.WINDOWS -> openExternalUrlWithProcess(
            ProcessCommand(
                executable = "rundll32.exe",
                arguments = listOf("url.dll,FileProtocolHandler", url),
            ),
        )

        else -> OpenExternalUrlResult.Failed(
            "Opening external URLs is unsupported on this host.",
        )
    }
