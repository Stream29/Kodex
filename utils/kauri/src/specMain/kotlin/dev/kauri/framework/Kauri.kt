@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package dev.kauri.framework

import kotlinx.io.Source

/**
 * Opens a resource linked into the caller's executable, or returns null when absent.
 * Readers have independent cursors and must be closed by the caller. Opening does not copy the payload;
 * reads copy bounded segments. The caller owns HTTP path validation, MIME and request authorization.
 *
 * @throws IllegalStateException if the linked pack is malformed.
 */
public expect fun openEmbeddedResource(path: String): Source?

/**
 * One system WebView for an explicit-port loopback HTTP URL, using a disposable browser context.
 * Construction acquires no window or GUI library resources. This owns neither a server nor backend work.
 * macOS uses WKWebView, Windows needs WebView2 Runtime, and Linux loads GTK/WebKitGTK only when run.
 *
 * @throws IllegalArgumentException if the URL is not accepted loopback HTTP or the title is blank/contains NUL.
 */
public expect class WebViewWindow(url: String, title: String) {
    /**
     * Creates, runs and cleans up the window on the process main thread; returns after cleanup.
     * A prior close request makes this a no-op, but still consumes the single allowed run.
     * Native initialization/navigation/content-process failures propagate after cleanup.
     *
     * @throws IllegalStateException if off the main thread, already run, another window is active,
     * or the required native runtime/display cannot initialize.
     */
    public fun run()

    /** Thread-safe, idempotent close request. Does not exit the process or stop a backend/server. */
    public fun requestClose()
}
