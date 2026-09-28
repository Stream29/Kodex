/*
 * Copyright 2023-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.rpc.internal.utils.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.rpc.internal.utils.InternalRpcApi::class)
class SynchronizedHashMapTest {
    @Test
    fun accessorsReturnStableSnapshotsAfterWrites() {
        val map = SynchronizedHashMap<Int, String>()
        map[1] = "one"
        map[2] = "two"

        val keys = map.keys
        val values = map.values
        val entries = map.entries
        val keyIterator = keys.iterator()
        val valueIterator = values.iterator()

        map.remove(1)
        map[3] = "three"

        assertEquals(setOf(1, 2), keys.toSet())
        assertEquals(setOf("one", "two"), values.toSet())
        assertEquals(
            setOf(
                RpcInternalConcurrentHashMap.Entry(1, "one"),
                RpcInternalConcurrentHashMap.Entry(2, "two"),
            ),
            entries,
        )
        assertEquals(setOf(1, 2), keyIterator.asSequence().toSet())
        assertEquals(setOf("one", "two"), valueIterator.asSequence().toSet())
        assertFalse(1 in map.keys)
        assertTrue(3 in map.keys)
    }
}
