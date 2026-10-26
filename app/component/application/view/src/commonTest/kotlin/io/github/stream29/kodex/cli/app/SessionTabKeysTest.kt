package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.*

val sessionTabKeysTest by testSuite {
    test("reorder preserves exact keys while departure and readmission allocate a new identity") {
        val first = Any()
        val second = Any()
        val keys = SessionTabKeys<Any>()
        val initial = keys.identify(listOf(first, second))
        assertEquals(initial.reversed(), keys.identify(listOf(second, first)))
        keys.identify(listOf(second))
        val readmitted = keys.identify(listOf(first, second))
        assertNotEquals(initial[0].second, readmitted[0].second)
        assertEquals(initial[1].second, readmitted[1].second)
        assertSame(first, readmitted[0].first)
    }
    test("value equality cannot merge distinct owners or resurrect a departed owner key") {
        data class EqualChild(val value: Int)
        val first = EqualChild(1)
        val second = EqualChild(1)
        val keys = SessionTabKeys<EqualChild>()
        val initial = keys.identify(listOf(first, second))
        assertNotEquals(initial[0].second, initial[1].second)
        assertTrue(keys.identify(emptyList()).isEmpty())
        val later = keys.identify(listOf(second, first))
        assertNotEquals(initial[1].second, later[0].second)
        assertNotEquals(initial[0].second, later[1].second)
        assertSame(second, later[0].first)
    }
}
