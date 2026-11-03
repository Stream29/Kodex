package io.github.stream29.kodex.cli.app

import dev.kauri.framework.WebViewWindow
import io.github.stream29.kodex.utils.externalurl.OpenExternalUrlResult
import io.github.stream29.kodex.utils.externalurl.openExternalUrl
import kotlinx.coroutines.*

internal expect val defaultLocalAppEngine: LocalAppEngine
internal expect val chromeAppSupported: Boolean
internal expect suspend fun runChromeApp(url: String, stopRequested: () -> Boolean): Int

public actual fun resolveLocalAppEngine(requested: LocalAppEngine): LocalAppEngine {
    val resolved = if (requested == LocalAppEngine.Default) defaultLocalAppEngine else requested
    require(resolved != LocalAppEngine.Chrome || chromeAppSupported) {
        "The Chrome application engine is supported only on Linux."
    }
    return resolved
}

public actual suspend fun runLocalWebFrontend(
    url: String,
    window: Boolean,
    stopRequested: () -> Boolean,
    engine: LocalAppEngine,
): Int = coroutineScope {
    if (!window) {
        when (val opened = openExternalUrl(url)) {
            OpenExternalUrlResult.Started ->
                println("Kodex Web frontend is serving until Ctrl+C. Closing the browser does not stop this host or backend.")
            is OpenExternalUrlResult.Failed -> error(opened.message)
        }
        while (!stopRequested()) delay(100)
        130
    } else if (resolveLocalAppEngine(engine) == LocalAppEngine.Chrome) {
        runChromeApp(url, stopRequested)
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
