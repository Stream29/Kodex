@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kauri.framework

import dev.kauri.gtk.*
import kotlinx.cinterop.*
import platform.posix.RTLD_LOCAL
import platform.posix.RTLD_NOW
import platform.posix.dlopen
import platform.posix.dlsym

// Keep both successful handles and failure for process lifetime. Do not unload native callback code.
private val loadedGtk by lazy { runCatching { GtkBindings() } }
internal val Gtk: GtkBindings get() = loadedGtk.getOrThrow()

internal class GtkBindings {
    private val libraries = listOf("libgtk-3.so.0", "libwebkit2gtk-4.1.so.0").map { name ->
        dlopen(name, RTLD_NOW or RTLD_LOCAL)
            ?: error("kodex app requires a working system GTK3/WebKitGTK4.1 runtime: cannot load $name")
    }

    private fun address(name: String): COpaquePointer =
        libraries.firstNotNullOfOrNull { dlsym(it, name) }
            ?: error("System WebView runtime is missing required symbol $name")

    val gtk_init_check = address("gtk_init_check")
        .reinterpret<CFunction<(COpaquePointer?, COpaquePointer?) -> Int>>()
    val gtk_window_new = address("gtk_window_new").reinterpret<CFunction<(Int) -> CPointer<GtkWidget>?>>()
    val gtk_window_set_title = address("gtk_window_set_title")
        .reinterpret<CFunction<(CPointer<GtkWindow>?, CPointer<ByteVar>?) -> Unit>>()
    val gtk_window_set_default_size = address("gtk_window_set_default_size")
        .reinterpret<CFunction<(CPointer<GtkWindow>?, Int, Int) -> Unit>>()
    val gtk_container_add = address("gtk_container_add")
        .reinterpret<CFunction<(CPointer<GtkContainer>?, CPointer<GtkWidget>?) -> Unit>>()
    val gtk_widget_show_all = address("gtk_widget_show_all").reinterpret<CFunction<(CPointer<GtkWidget>?) -> Unit>>()
    val gtk_widget_destroy = address("gtk_widget_destroy").reinterpret<CFunction<(CPointer<GtkWidget>?) -> Unit>>()
    val gtk_widget_has_focus = address("gtk_widget_has_focus").reinterpret<CFunction<(CPointer<GtkWidget>?) -> Int>>()
    val gdk_event_get_keyval = address("gdk_event_get_keyval")
        .reinterpret<CFunction<(CPointer<GdkEvent>?, CPointer<UIntVar>?) -> Int>>()
    val gdk_event_get_state = address("gdk_event_get_state")
        .reinterpret<CFunction<(CPointer<GdkEvent>?, CPointer<UIntVar>?) -> Int>>()
    val gtk_main = address("gtk_main").reinterpret<CFunction<() -> Unit>>()
    val gtk_main_quit = address("gtk_main_quit").reinterpret<CFunction<() -> Unit>>()
    val gtk_main_level = address("gtk_main_level").reinterpret<CFunction<() -> UInt>>()
    val g_timeout_add_full = address("g_timeout_add_full").reinterpret<CFunction<(
        Int, UInt, CPointer<CFunction<(COpaquePointer?) -> Int>>?, COpaquePointer?,
        CPointer<CFunction<(COpaquePointer?) -> Unit>>?,
    ) -> UInt>>()
    val g_source_remove = address("g_source_remove").reinterpret<CFunction<(UInt) -> Int>>()
    val webkit_web_context_new_ephemeral = address("webkit_web_context_new_ephemeral")
        .reinterpret<CFunction<() -> CPointer<WebKitWebContext>?>>()
    val webkit_network_error_quark = address("webkit_network_error_quark").reinterpret<CFunction<() -> UInt>>()
    val webkit_policy_error_quark = address("webkit_policy_error_quark").reinterpret<CFunction<() -> UInt>>()
    val webkit_web_view_new_with_context = address("webkit_web_view_new_with_context")
        .reinterpret<CFunction<(CPointer<WebKitWebContext>?) -> CPointer<GtkWidget>?>>()
    val webkit_web_view_load_uri = address("webkit_web_view_load_uri")
        .reinterpret<CFunction<(CPointer<WebKitWebView>?, CPointer<ByteVar>?) -> Unit>>()
    val webkit_web_view_get_uri = address("webkit_web_view_get_uri")
        .reinterpret<CFunction<(CPointer<WebKitWebView>?) -> CPointer<ByteVar>?>>()
    val webkit_policy_decision_ignore = address("webkit_policy_decision_ignore")
        .reinterpret<CFunction<(CPointer<WebKitPolicyDecision>?) -> Unit>>()
    val webkit_navigation_policy_decision_get_request = address("webkit_navigation_policy_decision_get_request")
        .reinterpret<CFunction<(CPointer<WebKitNavigationPolicyDecision>?) -> CPointer<WebKitURIRequest>?>>()
    val webkit_uri_request_get_uri = address("webkit_uri_request_get_uri")
        .reinterpret<CFunction<(CPointer<WebKitURIRequest>?) -> CPointer<ByteVar>?>>()
    val webkit_web_view_get_settings = address("webkit_web_view_get_settings")
        .reinterpret<CFunction<(CPointer<WebKitWebView>?) -> CPointer<WebKitSettings>?>>()
    val webkit_web_view_get_editor_state = address("webkit_web_view_get_editor_state")
        .reinterpret<CFunction<(CPointer<WebKitWebView>?) -> CPointer<WebKitEditorState>?>>()
    val webkit_editor_state_is_undo_available = address("webkit_editor_state_is_undo_available")
        .reinterpret<CFunction<(CPointer<WebKitEditorState>?) -> Int>>()
    val webkit_editor_state_is_redo_available = address("webkit_editor_state_is_redo_available")
        .reinterpret<CFunction<(CPointer<WebKitEditorState>?) -> Int>>()
    val webkit_web_view_execute_editing_command = address("webkit_web_view_execute_editing_command")
        .reinterpret<CFunction<(CPointer<WebKitWebView>?, CPointer<ByteVar>?) -> Unit>>()
    val webkit_settings_set_javascript_can_open_windows_automatically =
        address("webkit_settings_set_javascript_can_open_windows_automatically")
            .reinterpret<CFunction<(CPointer<WebKitSettings>?, Int) -> Unit>>()
    val g_object_ref_sink = address("g_object_ref_sink").reinterpret<CFunction<(COpaquePointer?) -> COpaquePointer?>>()
    val g_object_unref = address("g_object_unref").reinterpret<CFunction<(COpaquePointer?) -> Unit>>()
    val g_signal_handler_disconnect = address("g_signal_handler_disconnect")
        .reinterpret<CFunction<(COpaquePointer?, ULong) -> Unit>>()
    val g_signal_handler_is_connected = address("g_signal_handler_is_connected")
        .reinterpret<CFunction<(COpaquePointer?, ULong) -> Int>>()
    val g_signal_connect_data = address("g_signal_connect_data").reinterpret<CFunction<(
        COpaquePointer?, CPointer<ByteVar>?, CPointer<CFunction<() -> Unit>>?, COpaquePointer?,
        CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>?, Int,
    ) -> ULong>>()
}
