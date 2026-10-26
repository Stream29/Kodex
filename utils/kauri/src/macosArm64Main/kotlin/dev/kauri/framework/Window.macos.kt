@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kauri.framework

import kotlinx.cinterop.*
import platform.AppKit.*
import platform.AppKit.NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular
import platform.Foundation.*
import platform.WebKit.*
import platform.WebKit.WKNavigationActionPolicy.WKNavigationActionPolicyAllow
import platform.WebKit.WKNavigationActionPolicy.WKNavigationActionPolicyCancel
import platform.darwin.NSObject

internal actual fun isWindowThread(): Boolean = NSThread.isMainThread

private class WindowDelegate(
    private val input: WebViewWindow,
    private val application: NSApplication,
) : NSObject(), WKNavigationDelegateProtocol, WKUIDelegateProtocol, NSWindowDelegateProtocol {
    var finished = false
    var failure: Throwable? = null
    var running = false
    var loaded = false
    var nativeWindow: NSWindow? = null

    fun finish(error: Throwable? = null) {
        if (error != null && failure == null) failure = error
        if (finished) return
        finished = true
        nativeWindow?.close()
        if (running) {
            application.stop(null)
            NSEvent.otherEventWithType(
                NSEventTypeApplicationDefined, NSMakePoint(0.0, 0.0), 0u, 0.0,
                0, null, 0, 0, 0,
            )?.let { application.postEvent(it, true) }
        }
    }

    override fun windowWillClose(notification: NSNotification) {
        nativeWindow = null
        finish()
    }

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val allowed = allowsDocument(decidePolicyForNavigationAction.request.URL?.absoluteString, input.origin)
        if (!allowed) blockedDocument(false)
        decisionHandler(if (allowed) WKNavigationActionPolicyAllow else WKNavigationActionPolicyCancel)
    }

    override fun webView(
        webView: WKWebView,
        createWebViewWithConfiguration: WKWebViewConfiguration,
        forNavigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures,
    ): WKWebView? {
        blockedDocument(true)
        return null
    }

    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        loaded = allowsDocument(webView.URL?.absoluteString, input.origin)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) {
        if (!loaded && withError.code != NSURLErrorCancelled && !input.closing) {
            finish(IllegalStateException("Initial WebView navigation failed (${withError.code})"))
        }
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) {
        if (!loaded && withError.code != NSURLErrorCancelled && !input.closing) {
            finish(IllegalStateException("Initial WebView navigation failed (${withError.code})"))
        }
    }

    override fun webViewWebContentProcessDidTerminate(webView: WKWebView) {
        finish(IllegalStateException("WebView content process terminated"))
    }
}

internal actual fun runPlatformWindow(window: WebViewWindow) {
    val application = NSApplication.sharedApplication
    application.setActivationPolicy(NSApplicationActivationPolicyRegular)
    val delegate = WindowDelegate(window, application)
    var nativeWindow: NSWindow? = null
    var webView: WKWebView? = null
    var timer: NSTimer? = null
    try {
        val configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = true
        val frame = NSMakeRect(100.0, 100.0, 1180.0, 860.0)
        val native = NSWindow(
            contentRect = frame,
            styleMask = NSWindowStyleMaskTitled or NSWindowStyleMaskClosable or NSWindowStyleMaskResizable,
            backing = NSBackingStoreBuffered,
            defer = false,
        )
        nativeWindow = native
        native.releasedWhenClosed = false
        native.title = window.title
        native.delegate = delegate
        delegate.nativeWindow = native
        val view = WKWebView(NSMakeRect(0.0, 0.0, 1180.0, 860.0), configuration)
        webView = view
        view.autoresizingMask = NSViewWidthSizable or NSViewHeightSizable
        view.navigationDelegate = delegate
        view.UIDelegate = delegate
        native.contentView = view
        timer = NSTimer.scheduledTimerWithTimeInterval(0.03, true) {
            if (window.closing) delegate.finish()
        }
        val address = checkNotNull(NSURL.URLWithString(window.url)) { "Invalid initial WebView address" }
        view.loadRequest(NSURLRequest.requestWithURL(address))
        native.makeKeyAndOrderFront(null)
        application.activateIgnoringOtherApps(true)
        if (!window.closing && !delegate.finished) {
            delegate.running = true
            application.run()
        }
        delegate.failure?.let { throw it }
    } finally {
        delegate.running = false
        timer?.invalidate()
        webView?.stopLoading()
        webView?.navigationDelegate = null
        webView?.UIDelegate = null
        nativeWindow?.contentView = null
        nativeWindow?.delegate = null
        nativeWindow?.close()
        delegate.nativeWindow = null
    }
}
