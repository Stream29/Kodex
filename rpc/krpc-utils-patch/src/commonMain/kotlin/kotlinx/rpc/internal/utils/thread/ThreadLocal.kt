@file:Suppress("detekt.MatchingDeclarationName", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

/*
 * Copyright 2023-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.rpc.internal.utils.thread

import kotlinx.rpc.internal.utils.InternalRpcApi
import kotlinx.rpc.internal.utils.map.RpcInternalConcurrentHashMap

@InternalRpcApi
public actual class RpcInternalThreadLocal<T : Any> actual constructor() {
    private val map = RpcInternalConcurrentHashMap<Long, T>()

    public actual fun get(): T? {
        val threadId = currentThreadId()
        return map[threadId]
    }

    public actual fun set(value: T) {
        val threadId = currentThreadId()
        map[threadId] = value
    }

    public actual fun remove() {
        val threadId = currentThreadId()
        map.remove(threadId)
    }
}
