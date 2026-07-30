package io.github.stream29.kodex.utils.shellclient

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val ShellPipeIoDispatcher: CoroutineDispatcher =
    Dispatchers.Default.limitedParallelism(64, "Kodex.ShellPipeIO")
