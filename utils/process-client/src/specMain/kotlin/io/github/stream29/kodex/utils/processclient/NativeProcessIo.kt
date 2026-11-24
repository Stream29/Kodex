package io.github.stream29.kodex.utils.processclient

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSink
import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineRawSource

/** Low-level endpoint adapter for the platform process and terminal implementations. */
@RequiresOptIn
public annotation class NativeProcessAdapterApi

@NativeProcessAdapterApi
public interface NativeProcessStream : CoroutineRawSource, CoroutineRawSink {
    /** Closes admission and requests cancellation of pending I/O without waiting for it. */
    public fun requestClose()

    /** Requests close and waits for deregistration, native I/O and handle release, even on cancellation. */
    override suspend fun close()
}

/** Readiness-based endpoints can drain already available output after the root process exits. */
@NativeProcessAdapterApi
public interface NativeReadyProcessStream : NativeProcessStream {
    /** Wakes the reader. After buffered data, the first would-block read becomes EOF. */
    public fun finishReadsWithoutWaiting()
}

/**
 * Registers one owned POSIX fd or Windows overlapped pipe handle (encoded as address bits).
 * [terminal] enables terminal-specific EOF handling where needed. The caller retains the
 * handle on failure; on success it must close it through the returned endpoint only.
 */
@NativeProcessAdapterApi
public expect fun adoptNativeProcessStream(handle: Long, terminal: Boolean): NativeProcessStream

@NativeProcessAdapterApi
public interface NativeProcessExit {
    /** Awaits the root exit status independently of its pipe or terminal readers. */
    public suspend fun await(): Int

    /** Requests process-tree termination, serialized with native exit observation. */
    public fun terminate()

    /**
     * Releases native observation resources, including draining registered callbacks before
     * borrowed handles can be released. Call [await] first when a real exit status is needed.
     * POSIX additionally reaps the child before this returns.
     */
    public suspend fun close()
}

/**
 * Registers the POSIX child PID or Windows process handle (address bits). [processTree] is
 * the POSIX process group ID or Windows Job Object handle (address bits), or zero if absent.
 * A successful POSIX registration owns reaping; Windows handles are borrowed through [NativeProcessExit.close].
 * The caller must terminate a live child before closing its watcher during cancellation.
 */
@NativeProcessAdapterApi
public expect fun watchNativeProcessExit(process: Long, processTree: Long): NativeProcessExit
