package io.github.stream29.kodex.utils.shellclient

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

internal actual val ShellPipeIoDispatcher: CoroutineDispatcher =
    Dispatchers.IO.limitedParallelism(Int.MAX_VALUE, "Kodex.ShellPipeIO")
