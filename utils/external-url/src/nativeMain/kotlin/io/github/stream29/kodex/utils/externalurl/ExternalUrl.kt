package io.github.stream29.kodex.utils.externalurl

import io.github.stream29.kodex.utils.processclient.ProcessClient
import io.github.stream29.kodex.utils.processclient.ProcessCommand
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext

@OptIn(ExperimentalNativeApi::class)
public actual suspend fun openExternalUrl(url: String): OpenExternalUrlResult {
    if (url.isBlank()) return OpenExternalUrlResult.Failed("The URL must not be blank.")
    val command = when (Platform.osFamily) {
        OsFamily.MACOSX -> ProcessCommand(
            executable = "open",
            arguments = listOf(url),
        )

        OsFamily.LINUX -> ProcessCommand(
            executable = "xdg-open",
            arguments = listOf(url),
        )

        OsFamily.WINDOWS -> ProcessCommand(
            executable = "rundll32.exe",
            arguments = listOf("url.dll,FileProtocolHandler", url),
        )

        else -> return OpenExternalUrlResult.Failed(
            "Opening external URLs is unsupported on this host.",
        )
    }
    return openExternalUrlWithProcess(command)
}

private suspend fun openExternalUrlWithProcess(
    command: ProcessCommand,
): OpenExternalUrlResult {
    val client = CoroutineScope(currentCoroutineContext()).ProcessClient()
    return try {
        val process = client.start(command)
        try {
            val exitCode = process.exitCode.await()
            if (exitCode == 0) {
                OpenExternalUrlResult.Started
            } else {
                OpenExternalUrlResult.Failed(
                    "The system URL opener exited with status $exitCode.",
                )
            }
        } finally {
            process.close()
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Throwable) {
        OpenExternalUrlResult.Failed("The system URL opener could not be started.")
    } finally {
        client.close()
    }
}
