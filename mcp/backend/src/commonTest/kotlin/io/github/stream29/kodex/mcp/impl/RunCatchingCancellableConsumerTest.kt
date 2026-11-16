package io.github.stream29.kodex.mcp.impl

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.coroutines.runCatchingCancellable
import kotlinx.coroutines.yield
import kotlin.test.assertEquals

val runCatchingCancellableConsumerTest by testSuite {
    test("supports suspending calls across the module boundary") {
        val result = runCatchingCancellable {
            yield()
            42
        }

        assertEquals(42, result.getOrThrow())
    }

    test("supports non-local return across the module boundary") {
        assertEquals(42, returnFromBlock())
    }
}

private fun returnFromBlock(): Int {
    runCatchingCancellable { return 42 }
    return -1
}
