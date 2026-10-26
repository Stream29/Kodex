package io.github.stream29.kodex.cli.app

import dev.kauri.framework.WebViewWindow
import io.github.stream29.kodex.utils.externalurl.OpenExternalUrlResult
import io.github.stream29.kodex.utils.externalurl.openExternalUrl
import kotlinx.coroutines.*

public actual suspend fun runLocalWebFrontend(
    url: String,
    window: Boolean,
    stopRequested: () -> Boolean,
): Int = coroutineScope {
    if (!window) {
        when (val opened = openExternalUrl(url)) {
            OpenExternalUrlResult.Started ->
                println("Kodex Web frontend is serving until Ctrl+C. Closing the browser does not stop this host or backend.")
            is OpenExternalUrlResult.Failed -> error(opened.message)
        }
        while (!stopRequested()) delay(100)
        130
    } else {
        val frontend = WebViewWindow(url, "Kodex")
        val signals = launch(Dispatchers.Default) {
            while (!stopRequested()) delay(50)
            frontend.requestClose()
        }
        try { frontend.run() }
        finally { withContext(NonCancellable) { signals.cancelAndJoin() } }
        if (stopRequested()) 130 else 0
    }
}
