package io.github.stream29.kodex.agentstate.impl

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
val kodexRequestProjectionTest by testSuite {
    test("storage identity projects to a stable provider thread id") {
        val first = "filesystem:/tmp/kodex/session-1".toCodexThreadId()

        assertEquals(first, "filesystem:/tmp/kodex/session-1".toCodexThreadId())
        assertNotEquals(first, "filesystem:/tmp/kodex/session-2".toCodexThreadId())
        assertEquals(first, Uuid.parse(first).toString())
    }
}
