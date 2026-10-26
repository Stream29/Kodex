package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

val agentDurationTest by testSuite {
    test("live duration rounds ties away from zero without changing infinity") {
        for ((input, expected) in listOf(0 to 0, 499 to 0, 500 to 1, 1_499 to 1, 1_500 to 2, -499 to 0, -500 to -1, -1_500 to -2)) {
            assertEquals(expected.seconds, input.milliseconds.roundToSeconds())
        }
        assertEquals(Duration.INFINITE, Duration.INFINITE.roundToSeconds())
        assertEquals(-Duration.INFINITE, (-Duration.INFINITE).roundToSeconds())
        assertEquals("Worked for 2s", liveTurnDurationLabel(1_500.milliseconds))
    }
}
