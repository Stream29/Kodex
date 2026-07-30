package io.github.stream29.kodex.utils.externalurl

import io.github.stream29.kodex.utils.processclient.ProcessClient
import io.github.stream29.kodex.utils.processclient.ProcessCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext

internal suspend fun openExternalUrlWithProcess(
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
