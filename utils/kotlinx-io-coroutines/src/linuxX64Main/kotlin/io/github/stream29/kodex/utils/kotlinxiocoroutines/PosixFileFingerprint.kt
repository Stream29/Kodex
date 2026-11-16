@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.stream29.kodex.utils.kotlinxiocoroutines

import platform.posix.stat

internal actual fun stat.modifiedAtNanoseconds(): Long =
    st_mtim.tv_sec * NanosecondsPerSecond + st_mtim.tv_nsec

private const val NanosecondsPerSecond: Long = 1_000_000_000L
