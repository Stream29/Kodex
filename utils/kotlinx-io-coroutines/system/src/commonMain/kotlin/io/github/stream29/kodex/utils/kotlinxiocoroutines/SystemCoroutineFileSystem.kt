package io.github.stream29.kodex.utils.kotlinxiocoroutines

import kotlinx.coroutines.CoroutineDispatcher

internal const val CoroutineIoSegmentByteCount: Int = 64 * 1024

internal expect val IoDispatcher: CoroutineDispatcher
