package io.github.stream29.kodex.utils.kotlinxiocoroutines

import io.github.stream29.kodex.utils.coroutines.use as useCloseable

public actual suspend inline fun <T : CoroutineCloseable, R> T.use(block: suspend (T) -> R): R =
    useCloseable(block)
