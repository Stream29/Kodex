package io.github.stream29.codex.lite.utils.kotlinxiocoroutines

import kotlinx.io.files.SystemFileSystem

public actual val SystemCoroutineFileSystem: CoroutineFileSystem =
    BlockingCoroutineFileSystem(SystemFileSystem)
