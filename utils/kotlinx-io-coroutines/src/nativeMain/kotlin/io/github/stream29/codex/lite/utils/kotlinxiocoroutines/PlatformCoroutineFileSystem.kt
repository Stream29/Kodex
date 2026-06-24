package io.github.stream29.codex.lite.utils.kotlinxiocoroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
internal actual val IoDispatcher: CoroutineDispatcher =
    Dispatchers.Default.limitedParallelism(64, "CodexLite.FileIO")
