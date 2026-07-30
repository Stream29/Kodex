package io.github.stream29.codex.lite.utils.externalurl

import io.github.stream29.codex.lite.utils.processclient.ProcessCommand

internal actual suspend fun openExternalUrlOnHost(url: String): OpenExternalUrlResult {
    val operatingSystem = System.getProperty("os.name").orEmpty()
    return when {
        operatingSystem.startsWith("Mac", ignoreCase = true) ->
            openExternalUrlWithProcess(
                ProcessCommand(
                    executable = "open",
                    arguments = listOf(url),
                ),
            )

        operatingSystem.startsWith("Windows", ignoreCase = true) ->
            openExternalUrlWithProcess(
                ProcessCommand(
                    executable = "rundll32.exe",
                    arguments = listOf("url.dll,FileProtocolHandler", url),
                ),
            )

        operatingSystem.startsWith("Linux", ignoreCase = true) ->
            openExternalUrlWithProcess(
                ProcessCommand(
                    executable = "xdg-open",
                    arguments = listOf(url),
                ),
            )

        else -> OpenExternalUrlResult.Failed(
            "Opening external URLs is unsupported on this host.",
        )
    }
}
