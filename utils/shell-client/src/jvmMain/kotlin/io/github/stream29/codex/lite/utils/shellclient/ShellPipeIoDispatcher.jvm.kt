package io.github.stream29.codex.lite.utils.shellclient

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val ShellPipeIoDispatcher: CoroutineDispatcher = Dispatchers.IO
