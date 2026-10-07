package io.github.stream29.kodex.utils.kotlinxiocoroutines

import kotlinx.coroutines.CoroutineDispatcher

/** Platform default coroutine filesystem supplied by this implementation module. */
public expect val SystemCoroutineFileSystem: CoroutineFileSystem

internal const val CoroutineIoSegmentByteCount: Int = 64 * 1024

internal expect val IoDispatcher: CoroutineDispatcher
