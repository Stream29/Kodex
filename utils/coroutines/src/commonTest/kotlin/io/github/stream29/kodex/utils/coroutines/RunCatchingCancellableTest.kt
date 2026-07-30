package io.github.stream29.kodex.utils.coroutines

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

val runCatchingCancellableTest by testSuite {
    test("returns values from suspending callers") {
        val result = runCatchingCancellable {
            yield()
            42
        }

        assertEquals(42, result.getOrThrow())
    }

    test("captures ordinary failures") {
        val failure = IllegalStateException("failed")

        assertSame(
            failure,
            runCatchingCancellable<Unit> { throw failure }.exceptionOrNull(),
        )
    }

    test("propagates cancellation") {
        val cancellation = CancellationException("cancelled")

        assertSame(
            cancellation,
            assertFailsWith<CancellationException> {
                runCatchingCancellable<Unit> { throw cancellation }
            },
        )
    }
}
