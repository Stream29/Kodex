@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package dev.kauri.framework

import dev.kauri.gtk.*
import kotlinx.cinterop.*
import platform.posix.*
import kotlin.native.CpuArchitecture
import kotlin.native.Platform

internal actual fun isWindowThread(): Boolean =
    syscall(if (Platform.cpuArchitecture == CpuArchitecture.X64) 186L else 178L).toInt() == getpid()

private class GtkWindow(val input: WebViewWindow) {
    var widget: CPointer<GtkWidget>? = null
    var view: CPointer<WebKitWebView>? = null
    var destroyed = false
    var loaded = false
    var failure: Throwable? = null
    val signals = mutableListOf<Pair<COpaquePointer, ULong>>()

    fun failed(error: Throwable) { if (failure == null) failure = error }

    fun connect(
        instance: COpaquePointer, name: String, callback: CPointer<CFunction<() -> Unit>>,
        data: COpaquePointer, after: Boolean = false,
    ) {
        val id = memScoped { Gtk.g_signal_connect_data(instance, name.cstr.ptr, callback, data, null, if (after) 1 else 0) }
        check(id != 0uL) { "Cannot register native window signal" }
        signals.add(instance to id)
    }
}

private fun state(data: COpaquePointer?) = checkNotNull(data).asStableRef<GtkWindow>().get()

private fun onDestroy(widget: CPointer<GtkWidget>?, data: COpaquePointer?) {
    val state = state(data)
    state.destroyed = true
    if (Gtk.gtk_main_level() > 0u) Gtk.gtk_main_quit()
}

private fun onTick(data: COpaquePointer?): Int {
    val state = state(data)
    try {
        if (!state.destroyed && (state.input.closing || state.failure != null)) {
            state.widget?.let { Gtk.gtk_widget_destroy(it) }
        }
    } catch (error: Throwable) {
        state.failed(error)
        if (Gtk.gtk_main_level() > 0u) Gtk.gtk_main_quit()
    }
    return 1
}

private fun onEditingKey(widget: CPointer<GtkWidget>?, event: CPointer<GdkEvent>?, data: COpaquePointer?): Int {
    val state = state(data)
    if (event == null || state.destroyed || state.input.closing) return 0
    return try {
        val view = state.view ?: return 0
        if (Gtk.gtk_widget_has_focus(view.reinterpret()) == 0) return 0
        memScoped {
            val key = alloc<UIntVar>()
            val modifiers = alloc<UIntVar>()
            if (Gtk.gdk_event_get_keyval(event, key.ptr) == 0 || Gtk.gdk_event_get_state(event, modifiers.ptr) == 0) return 0
            val command = gtkEditingShortcut(key.value, modifiers.value) ?: return 0
            val editor = Gtk.webkit_web_view_get_editor_state(view) ?: return 0
            val available = when (command) {
                GtkEditingCommand.Undo -> Gtk.webkit_editor_state_is_undo_available(editor)
                GtkEditingCommand.Redo -> Gtk.webkit_editor_state_is_redo_available(editor)
            }
            if (available == 0) return 0
            Gtk.webkit_web_view_execute_editing_command(view, command.name.cstr.ptr)
            1
        }
    } catch (error: Throwable) {
        state.failed(error)
        0
    }
}

private fun onPolicy(
    view: CPointer<WebKitWebView>?, decision: CPointer<WebKitPolicyDecision>?, kind: Int, data: COpaquePointer?,
): Int {
    val state = state(data)
    return try {
        val popup = kind == WEBKIT_NEW_WINDOW
        val foreign = kind == WEBKIT_NAVIGATION && !allowsDocument(
            Gtk.webkit_uri_request_get_uri(Gtk.webkit_navigation_policy_decision_get_request(decision?.reinterpret()))?.toKString(),
            state.input.origin,
        )
        if (popup || foreign) {
            blockedDocument(popup)
            Gtk.webkit_policy_decision_ignore(decision)
            1
        } else 0
    } catch (error: Throwable) {
        state.failed(error)
        Gtk.webkit_policy_decision_ignore(decision)
        1
    }
}

private fun onLoadChanged(view: CPointer<WebKitWebView>?, event: Int, data: COpaquePointer?) {
    val state = state(data)
    if (event == WEBKIT_LOAD_FINISHED &&
        allowsDocument(Gtk.webkit_web_view_get_uri(view)?.toKString(), state.input.origin)) state.loaded = true
}

private fun onLoadFailed(
    view: CPointer<WebKitWebView>?, event: Int, uri: CPointer<ByteVar>?, error: CPointer<GError>?, data: COpaquePointer?,
): Int {
    val state = state(data)
    val cancelled = error != null &&
        ((error.pointed.domain == Gtk.webkit_network_error_quark() && error.pointed.code == WEBKIT_CANCELLED) ||
            (error.pointed.domain == Gtk.webkit_policy_error_quark() && error.pointed.code == WEBKIT_POLICY_INTERRUPTED))
    if (!state.loaded && !state.input.closing && !cancelled) {
        state.failed(IllegalStateException("Initial WebView navigation failed"))
    }
    return 1
}

private const val WEBKIT_NAVIGATION = 0
private const val WEBKIT_NEW_WINDOW = 1
private const val WEBKIT_LOAD_FINISHED = 3
private const val WEBKIT_CANCELLED = 302
private const val WEBKIT_POLICY_INTERRUPTED = 102

private fun onProcessTerminated(view: CPointer<WebKitWebView>?, reason: Int, data: COpaquePointer?) {
    state(data).failed(IllegalStateException("WebView content process terminated"))
}

private fun onCreate(
    view: CPointer<WebKitWebView>?, action: CPointer<WebKitNavigationAction>?, data: COpaquePointer?,
): CPointer<GtkWidget>? {
    blockedDocument(true)
    return null
}

internal actual fun runPlatformWindow(window: WebViewWindow) {
    check(Gtk.gtk_init_check(null, null) != 0) { "Cannot initialize GTK display" }
    val state = GtkWindow(window)
    val reference = StableRef.create(state)
    var context: CPointer<WebKitWebContext>? = null
    var timer = 0u
    try {
        val widget = checkNotNull(Gtk.gtk_window_new(0))
        state.widget = widget
        Gtk.g_object_ref_sink(widget)
        memScoped { Gtk.gtk_window_set_title(widget.reinterpret(), window.title.cstr.ptr) }
        Gtk.gtk_window_set_default_size(widget.reinterpret(), 1180, 860)
        context = checkNotNull(Gtk.webkit_web_context_new_ephemeral())
        val child = checkNotNull(Gtk.webkit_web_view_new_with_context(context))
        val view = child.reinterpret<WebKitWebView>()
        state.view = view
        Gtk.g_object_ref_sink(view)
        Gtk.gtk_container_add(widget.reinterpret(), child)
        Gtk.webkit_settings_set_javascript_can_open_windows_automatically(Gtk.webkit_web_view_get_settings(view), 1)
        val data = reference.asCPointer()
        state.connect(widget, "destroy", staticCFunction(::onDestroy).reinterpret(), data)
        state.connect(view, "decide-policy", staticCFunction(::onPolicy).reinterpret(), data)
        state.connect(view, "create", staticCFunction(::onCreate).reinterpret(), data)
        state.connect(view, "load-changed", staticCFunction(::onLoadChanged).reinterpret(), data)
        state.connect(view, "load-failed", staticCFunction(::onLoadFailed).reinterpret(), data)
        state.connect(view, "web-process-terminated", staticCFunction(::onProcessTerminated).reinterpret(), data)
        // Let WebKit and its input method handle the event first; only fill an unhandled editing shortcut.
        state.connect(widget, "key-press-event", staticCFunction(::onEditingKey).reinterpret(), data, after = true)
        timer = Gtk.g_timeout_add_full(0, 30u, staticCFunction(::onTick), data, null)
        check(timer != 0u) { "Cannot schedule native close polling" }
        if (!window.closing) {
            Gtk.gtk_widget_show_all(widget)
            memScoped { Gtk.webkit_web_view_load_uri(view, window.url.cstr.ptr) }
            Gtk.gtk_main()
        }
        state.failure?.let { throw it }
    } finally {
        if (timer != 0u) Gtk.g_source_remove(timer)
        state.signals.asReversed().forEach { (instance, signal) ->
            if (Gtk.g_signal_handler_is_connected(instance, signal) != 0) {
                Gtk.g_signal_handler_disconnect(instance, signal)
            }
        }
        if (!state.destroyed) state.widget?.let { Gtk.gtk_widget_destroy(it) }
        state.view?.let { Gtk.g_object_unref(it) }
        state.widget?.let { Gtk.g_object_unref(it) }
        context?.let { Gtk.g_object_unref(it) }
        reference.dispose()
    }
}
