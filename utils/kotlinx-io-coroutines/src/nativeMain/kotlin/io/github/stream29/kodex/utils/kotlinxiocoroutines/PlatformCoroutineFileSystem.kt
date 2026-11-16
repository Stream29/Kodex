package io.github.stream29.kodex.utils.kotlinxiocoroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO

@OptIn(ExperimentalCoroutinesApi::class)
internal actual val IoDispatcher: CoroutineDispatcher =
    Dispatchers.IO.limitedParallelism(Int.MAX_VALUE, "Kodex.FileIO")
