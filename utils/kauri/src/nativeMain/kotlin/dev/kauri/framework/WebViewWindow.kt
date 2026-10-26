@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package dev.kauri.framework

import kotlin.concurrent.atomics.AtomicBoolean

public actual class WebViewWindow public actual constructor(
    internal val url: String,
    internal val title: String,
) {
    internal val origin = localOrigin(url)
        ?: throw IllegalArgumentException("Expected an explicit-port loopback HTTP URL")
    private val started = AtomicBoolean(false)
    private val stop = AtomicBoolean(false)
    internal val closing: Boolean get() = stop.load()

    init {
        require(title.isNotBlank() && '\u0000' !in title) { "Expected a nonblank window title" }
    }

    public actual fun run() {
        check(isWindowThread()) { "Run the window on the process main thread" }
        check(started.compareAndSet(false, true)) { "A window can run only once" }
        if (closing) return
        check(activeWindow.compareAndSet(false, true)) { "Only one active window is supported" }
        try {
            if (!closing) runPlatformWindow(this)
        } finally {
            activeWindow.store(false)
        }
    }

    public actual fun requestClose() {
        stop.store(true)
    }
}

private val activeWindow = AtomicBoolean(false)
private val loopbackUrl = Regex("""^http://127\.0\.0\.1(?::([0-9]{1,5}))?(?:[/?#][^\s\\\u0000]*)?$""")

internal fun localOrigin(url: String, requirePort: Boolean = true): String? {
    val match = loopbackUrl.matchEntire(url) ?: return null
    val digits = match.groupValues[1]
    if (requirePort && digits.isEmpty()) return null
    val port = if (digits.isEmpty()) 80 else digits.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    return "http://127.0.0.1:$port"
}

internal fun allowsDocument(url: String?, origin: String): Boolean =
    url != null && localOrigin(url, requirePort = false) == origin

internal fun blockedDocument(popup: Boolean) {
    println(if (popup) "KAURI_POLICY blocked popup" else "KAURI_POLICY blocked navigation")
}

internal expect fun isWindowThread(): Boolean
internal expect fun runPlatformWindow(window: WebViewWindow)
