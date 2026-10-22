/*
 * Copyright 2023-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.rpc.internal.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.rpc.internal.utils.hex.rpcInternalHexToReadableBinary
import kotlinx.rpc.internal.utils.map.RpcInternalConcurrentHashMap
import kotlinx.rpc.internal.utils.thread.RpcInternalThreadLocal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(InternalRpcApi::class)
class KrpcUtilsCompatibilityApiTest {
    @Test
    fun threadLocalCanBeReadSetAndRemoved() {
        val threadLocal = RpcInternalThreadLocal<String>()

        assertNull(threadLocal.get())
        threadLocal.set("value")
        assertEquals("value", threadLocal.get())
        threadLocal.remove()
        assertNull(threadLocal.get())
    }

    @Test
    fun deferredExtensionsReuseAndCompleteValues() {
        val deferredByKey = RpcInternalConcurrentHashMap<String, CompletableDeferred<String>>()
        val deferred = deferredByKey.getDeferred("key")

        assertSame(deferred, deferredByKey.getDeferred("key"))
        assertNull(deferred.getOrNull())

        deferredByKey["key"] = "complete"

        assertEquals("complete", deferred.getOrNull())

        val failure = IllegalStateException("failed")
        val failedDeferred = CompletableDeferred<String>()
        failedDeferred.completeExceptionally(failure)

        assertSame(failure, assertFailsWith<IllegalStateException> { failedDeferred.getOrNull() })
    }

    @Test
    fun hexConversionPreservesAsciiAndRejectsInvalidInput() {
        assertEquals(" A????", "20417f8081ff".rpcInternalHexToReadableBinary())
        assertFailsWith<IllegalArgumentException> {
            "0".rpcInternalHexToReadableBinary()
        }
    }
}
