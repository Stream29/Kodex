@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dev.kauri.framework

import dev.kauri.webview2.*
import kotlinx.cinterop.*
import platform.posix.memcmp
import platform.posix.memset
import platform.posix._GUID
import platform.windows.*
import kotlin.concurrent.atomics.AtomicInt
import kotlin.uuid.Uuid

internal actual fun isWindowThread(): Boolean = memScoped {
    val snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD.toUInt(), 0u)
    if (snapshot == INVALID_HANDLE_VALUE) return false
    var first = 0u
    var earliest = ULong.MAX_VALUE
    try {
        val entry = alloc<THREADENTRY32> { dwSize = sizeOf<THREADENTRY32>().toUInt() }
        val created = alloc<FILETIME>()
        val exited = alloc<FILETIME>()
        val kernel = alloc<FILETIME>()
        val user = alloc<FILETIME>()
        if (Thread32First(snapshot, entry.ptr) != 0) do {
            if (entry.th32OwnerProcessID == GetCurrentProcessId()) {
                val thread = OpenThread(0x0800u, 0, entry.th32ThreadID)
                if (thread != null) try {
                    if (GetThreadTimes(thread, created.ptr, exited.ptr, kernel.ptr, user.ptr) != 0) {
                        val time = (created.dwHighDateTime.toULong() shl 32) or created.dwLowDateTime.toULong()
                        if (time < earliest) { earliest = time; first = entry.th32ThreadID }
                    }
                } finally { CloseHandle(thread) }
            }
        } while (Thread32Next(snapshot, entry.ptr) != 0)
    } finally { CloseHandle(snapshot) }
    first != 0u && first == GetCurrentThreadId()
}

private class WinWindow(val input: WebViewWindow) {
    var hwnd: HWND? = null
    var environment: CPointer<ICoreWebView2Environment>? = null
    var controller: CPointer<ICoreWebView2Controller>? = null
    var core: CPointer<ICoreWebView2>? = null
    var stopping = false
    var loaded = false
    var failure: Throwable? = null
    val handlers = AtomicInt(0)
    val unregister = mutableListOf<() -> Unit>()

    fun failed(error: Throwable) {
        if (failure == null) failure = error
        hwnd?.let { PostMessageW(it, WM_CLOSE.toUInt(), 0u, 0) }
    }

    fun resize() = memScoped {
        controller?.let {
            val rectangle = alloc<RECT>()
            check(GetClientRect(hwnd, rectangle.ptr) != 0) { "Cannot read window bounds" }
            hr(it.pointed.lpVtbl!!.pointed.put_Bounds!!(it, rectangle.readValue()))
        }
    }

    fun register(
        handler: CPointer<*>,
        add: (CPointer<*>, CPointer<EventRegistrationToken>) -> Int,
        remove: (CValue<EventRegistrationToken>) -> Int,
    ) {
        try {
            memScoped {
                val token = alloc<EventRegistrationToken>()
                hr(add(handler, token.ptr))
                val saved = token.readValue()
                unregister.add { hr(remove(saved)) }
            }
        } finally { releaseHandler(handler) }
    }

    fun configure(view: CPointer<ICoreWebView2>) {
        val v = view.pointed.lpVtbl!!.pointed
        register(handler(this, IID_ICoreWebView2NavigationStartingEventHandler.ptr, navigationTable),
            { h, t -> v.add_NavigationStarting!!(view, h.reinterpret(), t) },
            { v.remove_NavigationStarting!!(view, it) })
        register(handler(this, IID_ICoreWebView2NewWindowRequestedEventHandler.ptr, popupTable),
            { h, t -> v.add_NewWindowRequested!!(view, h.reinterpret(), t) },
            { v.remove_NewWindowRequested!!(view, it) })
        register(handler(this, IID_ICoreWebView2NavigationCompletedEventHandler.ptr, completedTable),
            { h, t -> v.add_NavigationCompleted!!(view, h.reinterpret(), t) },
            { v.remove_NavigationCompleted!!(view, it) })
        register(handler(this, IID_ICoreWebView2ProcessFailedEventHandler.ptr, processTable),
            { h, t -> v.add_ProcessFailed!!(view, h.reinterpret(), t) },
            { v.remove_ProcessFailed!!(view, it) })
        memScoped { hr(v.Navigate!!(view, input.url.wcstr.ptr)) }
    }

    fun releaseInterfaces() {
        unregister.asReversed().forEach { action -> runCatching(action).onFailure(::failed) }
        unregister.clear()
        core?.let { it.pointed.lpVtbl!!.pointed.Release!!(it) }
        core = null
        controller?.let {
            runCatching { hr(it.pointed.lpVtbl!!.pointed.Close!!(it)) }.onFailure(::failed)
            it.pointed.lpVtbl!!.pointed.Release!!(it)
        }
        controller = null
        environment?.let { it.pointed.lpVtbl!!.pointed.Release!!(it) }
        environment = null
    }
}

private fun hr(value: Int) {
    check(value >= 0) { "WebView2 failed (HRESULT ${value.toUInt().toString(16)})" }
}

private class HandlerState(val window: WinWindow, val iid: CPointer<_GUID>)

private fun handler(window: WinWindow, iid: CPointer<_GUID>, table: CPointer<*>): CPointer<*> {
    val state = StableRef.create(HandlerState(window, iid))
    val block = nativeHeap.allocArray<LongVar>(3)
    block[0] = table.rawValue.toLong()
    block[1] = 1L
    block[2] = state.asCPointer().rawValue.toLong()
    window.handlers.fetchAndAdd(1)
    return block
}

private fun handlerState(pointer: COpaquePointer?): HandlerState =
    checkNotNull(checkNotNull(pointer).reinterpret<LongVar>()[2].toCPointer<COpaque>())
        .asStableRef<HandlerState>().get()

private fun addHandler(pointer: COpaquePointer?): UInt =
    InterlockedIncrement!!((checkNotNull(pointer).reinterpret<LongVar>() + 1)!!.reinterpret()).toUInt()

private fun releaseHandler(pointer: COpaquePointer?): UInt {
    val block = checkNotNull(pointer).reinterpret<LongVar>()
    val remaining = InterlockedDecrement!!((block + 1)!!.reinterpret())
    if (remaining == 0) {
        val reference = checkNotNull(block[2].toCPointer<COpaque>()).asStableRef<HandlerState>()
        reference.get().window.handlers.fetchAndAdd(-1)
        reference.dispose()
        nativeHeap.free(block)
    }
    return remaining.toUInt()
}

private fun queryHandler(pointer: COpaquePointer?, iid: CPointer<_GUID>?, output: CPointer<COpaquePointerVar>?): Int {
    if (output == null) return 0x80004003u.toInt()
    output.pointed.value = null
    if (pointer == null || iid == null) return 0x80004002u.toInt()
    val expected = handlerState(pointer).iid
    if (memcmp(iid, expected, sizeOf<_GUID>().convert()) != 0 &&
        memcmp(iid, IID_IUnknown.ptr, sizeOf<_GUID>().convert()) != 0) return 0x80004002u.toInt()
    output.pointed.value = pointer
    addHandler(pointer)
    return 0
}

private fun wire(table: CPointer<*>) {
    val prefix = table.reinterpret<IUnknownVtbl>().pointed
    prefix.QueryInterface = staticCFunction(::queryHandler).reinterpret()
    prefix.AddRef = staticCFunction(::addHandler).reinterpret()
    prefix.Release = staticCFunction(::releaseHandler).reinterpret()
}

private inline fun callback(pointer: COpaquePointer?, block: (WinWindow) -> Unit): Int {
    val owner = handlerState(pointer).window
    try { block(owner) } catch (error: Throwable) { owner.failed(error) }
    return 0
}

private fun environmentReady(
    self: CPointer<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>?, result: Int,
    environment: CPointer<ICoreWebView2Environment>?,
): Int = callback(self) { owner ->
    if (owner.stopping || owner.input.closing) return@callback
    hr(result)
    val env = checkNotNull(environment) { "WebView2 returned no environment" }
    env.pointed.lpVtbl!!.pointed.AddRef!!(env)
    owner.environment = env
    val complete = handler(owner, IID_ICoreWebView2CreateCoreWebView2ControllerCompletedHandler.ptr, controllerTable)
    try {
        hr(env.pointed.lpVtbl!!.pointed.CreateCoreWebView2Controller!!(env, owner.hwnd, complete.reinterpret()))
    } finally { releaseHandler(complete) }
}

private fun controllerReady(
    self: CPointer<ICoreWebView2CreateCoreWebView2ControllerCompletedHandler>?, result: Int,
    controller: CPointer<ICoreWebView2Controller>?,
): Int = callback(self) { owner ->
    if (owner.stopping || owner.input.closing) {
        controller?.let { it.pointed.lpVtbl!!.pointed.Close!!(it) }
        return@callback
    }
    hr(result)
    val control = checkNotNull(controller) { "WebView2 returned no controller" }
    val table = control.pointed.lpVtbl!!.pointed
    table.AddRef!!(control)
    owner.controller = control
    owner.resize()
    hr(table.put_IsVisible!!(control, 1))
    memScoped {
        val view = alloc<CPointerVar<ICoreWebView2>>()
        hr(table.get_CoreWebView2!!(control, view.ptr))
        owner.core = checkNotNull(view.value) { "WebView2 returned no view" }
        owner.configure(checkNotNull(owner.core))
    }
}

private fun navigationStarting(
    self: CPointer<ICoreWebView2NavigationStartingEventHandler>?, view: CPointer<ICoreWebView2>?,
    event: CPointer<ICoreWebView2NavigationStartingEventArgs>?,
): Int = callback(self) { owner ->
    val args = checkNotNull(event)
    memScoped {
        val value = alloc<CPointerVar<UShortVar>>()
        value.value = null
        try {
            hr(args.pointed.lpVtbl!!.pointed.get_Uri!!(args, value.ptr))
            if (!allowsDocument(value.value?.toKStringFromUtf16(), owner.input.origin)) {
                hr(args.pointed.lpVtbl!!.pointed.put_Cancel!!(args, 1))
                blockedDocument(false)
            }
        } finally { value.value?.let { CoTaskMemFree(it) } }
    }
}

private fun popupRequested(
    self: CPointer<ICoreWebView2NewWindowRequestedEventHandler>?, view: CPointer<ICoreWebView2>?,
    event: CPointer<ICoreWebView2NewWindowRequestedEventArgs>?,
): Int = callback(self) {
    val args = checkNotNull(event)
    hr(args.pointed.lpVtbl!!.pointed.put_Handled!!(args, 1))
    blockedDocument(true)
}

private fun navigationCompleted(
    self: CPointer<ICoreWebView2NavigationCompletedEventHandler>?, view: CPointer<ICoreWebView2>?,
    event: CPointer<ICoreWebView2NavigationCompletedEventArgs>?,
): Int = callback(self) { owner ->
    if (owner.stopping || owner.input.closing || owner.loaded) return@callback
    val args = checkNotNull(event)
    memScoped {
        val success = alloc<IntVar>()
        hr(args.pointed.lpVtbl!!.pointed.get_IsSuccess!!(args, success.ptr))
        if (success.value != 0) {
            val source = alloc<CPointerVar<UShortVar>>()
            source.value = null
            try {
                hr(checkNotNull(view).pointed.lpVtbl!!.pointed.get_Source!!(view, source.ptr))
                owner.loaded = allowsDocument(source.value?.toKStringFromUtf16(), owner.input.origin)
            } finally { source.value?.let { CoTaskMemFree(it) } }
        }
        else {
            val reason = alloc<UIntVar>()
            hr(args.pointed.lpVtbl!!.pointed.get_WebErrorStatus!!(args, reason.ptr))
            if (reason.value != COREWEBVIEW2_WEB_ERROR_STATUS_OPERATION_CANCELED) {
                error("Initial WebView navigation failed (${reason.value})")
            }
        }
    }
}

private fun processFailed(
    self: CPointer<ICoreWebView2ProcessFailedEventHandler>?, view: CPointer<ICoreWebView2>?,
    event: CPointer<ICoreWebView2ProcessFailedEventArgs>?,
): Int = callback(self) { owner ->
    if (!owner.stopping) error("WebView browser/content process failed")
}

private val environmentTable by lazy {
    nativeHeap.alloc<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandlerVtbl> {
        Invoke = staticCFunction(::environmentReady)
    }.ptr.also(::wire)
}
private val controllerTable by lazy {
    nativeHeap.alloc<ICoreWebView2CreateCoreWebView2ControllerCompletedHandlerVtbl> {
        Invoke = staticCFunction(::controllerReady)
    }.ptr.also(::wire)
}
private val navigationTable by lazy {
    nativeHeap.alloc<ICoreWebView2NavigationStartingEventHandlerVtbl> {
        Invoke = staticCFunction(::navigationStarting)
    }.ptr.also(::wire)
}
private val popupTable by lazy {
    nativeHeap.alloc<ICoreWebView2NewWindowRequestedEventHandlerVtbl> {
        Invoke = staticCFunction(::popupRequested)
    }.ptr.also(::wire)
}
private val completedTable by lazy {
    nativeHeap.alloc<ICoreWebView2NavigationCompletedEventHandlerVtbl> {
        Invoke = staticCFunction(::navigationCompleted)
    }.ptr.also(::wire)
}
private val processTable by lazy {
    nativeHeap.alloc<ICoreWebView2ProcessFailedEventHandlerVtbl> {
        Invoke = staticCFunction(::processFailed)
    }.ptr.also(::wire)
}

private fun windowProcedure(hwnd: HWND?, message: UInt, w: ULong, l: Long): Long {
    var state: WinWindow? = null
    try {
        if (message == WM_NCCREATE.toUInt()) {
            val parameters = checkNotNull(l.toCPointer<CREATESTRUCTW>()).pointed.lpCreateParams
            SetWindowLongPtrW(hwnd, GWLP_USERDATA, parameters?.rawValue?.toLong() ?: 0)
        }
        val pointer = GetWindowLongPtrW(hwnd, GWLP_USERDATA).toCPointer<COpaque>()
        state = pointer?.asStableRef<WinWindow>()?.get()
        when (message) {
            WM_SIZE.toUInt() -> if (state != null && !state.stopping) state.resize()
            WM_TIMER.toUInt() -> if (state?.input?.closing == true) PostMessageW(hwnd, WM_CLOSE.toUInt(), 0u, 0)
            WM_CLOSE.toUInt() -> {
                state?.stopping = true
                if (DestroyWindow(hwnd) == 0) {
                    state?.failed(IllegalStateException("Cannot destroy native window"))
                    PostQuitMessage(0)
                }
                return 0
            }
            WM_DESTROY.toUInt() -> {
                state?.hwnd = null
                PostQuitMessage(0)
                return 0
            }
            WM_NCDESTROY.toUInt() -> SetWindowLongPtrW(hwnd, GWLP_USERDATA, 0)
        }
    } catch (error: Throwable) {
        state?.failed(error)
    }
    return DefWindowProcW(hwnd, message, w, l)
}

private typealias CreateEnvironment = CFunction<(
    Boolean, Int, CPointer<UShortVar>?, COpaquePointer?,
    CPointer<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>?,
) -> Int>

private val loadedRuntimes = mutableMapOf<String, HMODULE>()

internal actual fun runPlatformWindow(window: WebViewWindow) {
    val owner = WinWindow(window)
    val className = "Kauri-${Uuid.random().toHexString()}"
    val reference = StableRef.create(owner)
    var initialized = false
    var registered = false
    var temporary: String? = null
    try {
        hr(OleInitialize(null))
        initialized = true
        val directory = newBrowserDirectory().also { temporary = it }
        val library = installedRuntime()
        val module = loadedRuntimes.getOrPut(library) {
            checkNotNull(LoadLibraryExW(library, null, 0x00000900u)) { "Cannot load installed WebView2 runtime" }
        }
        val create = checkNotNull(GetProcAddress(module, "CreateWebViewEnvironmentWithOptionsInternal")) {
            "Installed WebView2 has no supported environment entry"
        }.reinterpret<CreateEnvironment>()
        memScoped {
            val type = alloc<WNDCLASSEXW>()
            memset(type.ptr, 0, sizeOf<WNDCLASSEXW>().convert())
            type.cbSize = sizeOf<WNDCLASSEXW>().toUInt()
            type.lpfnWndProc = staticCFunction(::windowProcedure)
            type.hInstance = GetModuleHandleW(null)
            type.lpszClassName = className.wcstr.ptr
            check(RegisterClassExW(type.ptr) != 0.toUShort()) { "Cannot register native window" }
        }
        registered = true
        val handle = checkNotNull(CreateWindowExW(
            0u, className, window.title, WS_OVERLAPPEDWINDOW.toUInt(),
            CW_USEDEFAULT, CW_USEDEFAULT,
            1180, 860, null, null, GetModuleHandleW(null), reference.asCPointer(),
        )) { "Cannot create native window" }
        owner.hwnd = handle
        check(SetTimer(handle, 1u, 30u, null) != 0uL) { "Cannot schedule native close polling" }
        ShowWindow(handle, SW_SHOW)
        val complete = handler(owner, IID_ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler.ptr, environmentTable)
        try { memScoped { hr(create(true, 0, directory.wcstr.ptr, null, complete.reinterpret())) } }
        finally { releaseHandler(complete) }
        memScoped {
            val message = alloc<MSG>()
            while (true) {
                val result = GetMessageW(message.ptr, null, 0u, 0u)
                check(result >= 0) { "Native message loop failed" }
                if (result == 0) break
                TranslateMessage(message.ptr)
                DispatchMessageW(message.ptr)
            }
        }
    } catch (error: Throwable) {
        owner.failed(error)
    } finally {
        owner.stopping = true
        owner.hwnd?.let { KillTimer(it, 1u) }
        owner.releaseInterfaces()
        owner.hwnd?.let {
            SetWindowLongPtrW(it, GWLP_USERDATA, 0)
            if (DestroyWindow(it) == 0) owner.failed(IllegalStateException("Cannot destroy native window"))
            owner.hwnd = null
        }
        val deadline = GetTickCount64() + 5000u
        memScoped {
            val message = alloc<MSG>()
            do {
                while (PeekMessageW(message.ptr, null, 0u, 0u, PM_REMOVE.toUInt()) != 0) {
                    if (message.message != WM_QUIT.toUInt()) {
                        TranslateMessage(message.ptr)
                        DispatchMessageW(message.ptr)
                    }
                }
                if (owner.handlers.load() == 0) break
                Sleep(10u)
            } while (GetTickCount64() < deadline)
        }
        if (registered) UnregisterClassW(className, GetModuleHandleW(null))
        reference.dispose()
        if (initialized) OleUninitialize()
        temporary?.let {
            if (owner.handlers.load() == 0) {
                val deadline = GetTickCount64() + 2000u
                var result: Result<Unit>
                do {
                    result = runCatching { deleteBrowserDirectory(it) }
                    if (result.isSuccess) break
                    Sleep(50u)
                } while (GetTickCount64() < deadline)
                result.onFailure(owner::failed)
            }
            else owner.failed(IllegalStateException("WebView2 callbacks did not finish; temporary profile retained"))
        }
    }
    owner.failure?.let { throw it }
}

private fun environment(name: String): String? = memScoped {
    val buffer = allocArray<UShortVar>(32768)
    val count = GetEnvironmentVariableW(name, buffer, 32768u)
    if (count in 1u..32767u) buffer.toKStringFromUtf16() else null
}

private fun installedRuntime(): String {
    for (root in listOfNotNull(environment("PROGRAMFILES(X86)"), environment("PROGRAMFILES"), environment("LOCALAPPDATA"))) {
        if (!Regex("""^[A-Za-z]:[\\/].*""").matches(root)) continue
        val base = "$root\\Microsoft\\EdgeWebView\\Application"
        val versions = mutableListOf<String>()
        memScoped {
            val entry = alloc<WIN32_FIND_DATAW>()
            val search = FindFirstFileW("$base\\*", entry.ptr)
            if (search != INVALID_HANDLE_VALUE) try {
                do {
                    val name = entry.cFileName.toKStringFromUtf16()
                    if (Regex("""[0-9]+(?:\.[0-9]+){3}""").matches(name) &&
                        name.split('.').all { it.toUIntOrNull() != null }) versions.add(name)
                } while (FindNextFileW(search, entry.ptr) != 0)
            } finally { FindClose(search) }
        }
        versions.sortWith { a, b ->
            val left = a.split('.').map { it.toLong() }
            val right = b.split('.').map { it.toLong() }
            (0..3).firstNotNullOfOrNull { i -> right[i].compareTo(left[i]).takeIf { it != 0 } } ?: 0
        }
        for (version in versions) {
            val path = "$base\\$version\\EBWebView\\x64\\EmbeddedBrowserWebView.dll"
            if (GetFileAttributesW(path) != INVALID_FILE_ATTRIBUTES) return path
        }
    }
    error("Install Microsoft Edge WebView2 Runtime before running this application")
}

private fun newBrowserDirectory(): String = memScoped {
    val base = allocArray<UShortVar>(32768)
    check(GetTempPathW(32768u, base) in 1u..32767u) { "Cannot find temporary data location" }
    val path = "${base.toKStringFromUtf16()}kauri-browser-${Uuid.random().toHexString()}"
    check(CreateDirectoryW(path, null) != 0) { "Cannot create temporary browser directory" }
    path
}

private fun deleteBrowserDirectory(path: String): Unit = memScoped {
    val data = alloc<WIN32_FIND_DATAW>()
    val search = FindFirstFileW("$path\\*", data.ptr)
    if (search != INVALID_HANDLE_VALUE) try {
        do {
            val name = data.cFileName.toKStringFromUtf16()
            if (name != "." && name != "..") {
                val child = "$path\\$name"
                val directory = data.dwFileAttributes and FILE_ATTRIBUTE_DIRECTORY.toUInt() != 0u
                val reparse = data.dwFileAttributes and FILE_ATTRIBUTE_REPARSE_POINT.toUInt() != 0u
                if (directory && !reparse) deleteBrowserDirectory(child)
                else {
                    check(if (directory) RemoveDirectoryW(child) != 0 else DeleteFileW(child) != 0) {
                        "Cannot remove temporary browser entry"
                    }
                }
            }
        } while (FindNextFileW(search, data.ptr) != 0)
    } finally { FindClose(search) }
    check(RemoveDirectoryW(path) != 0) { "Cannot remove temporary browser directory" }
}
