package io.github.stream29.kodex.utils.kotlinxiocoroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val IoDispatcher: CoroutineDispatcher =
    Dispatchers.IO
