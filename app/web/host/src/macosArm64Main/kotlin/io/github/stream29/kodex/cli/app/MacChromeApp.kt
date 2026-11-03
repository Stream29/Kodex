package io.github.stream29.kodex.cli.app

internal actual val defaultLocalAppEngine: LocalAppEngine = LocalAppEngine.System
internal actual val chromeAppSupported: Boolean = false
internal actual suspend fun runChromeApp(url: String, stopRequested: () -> Boolean): Int =
    error("The Chrome application engine is supported only on Linux.")
