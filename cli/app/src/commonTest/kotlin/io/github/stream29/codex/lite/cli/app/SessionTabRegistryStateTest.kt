package io.github.stream29.codex.lite.cli.app

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals

val sessionTabRegistryStateTest by testSuite {
    test("new tabs remain ordered and independently selectable") {
        val first = SessionTabTarget.NewSession(id = 1, ordinal = 1)
        val second = SessionTabTarget.NewSession(id = 2, ordinal = 2)
        val state = SessionTabRegistryState(tabs = listOf(first), activeTarget = first)

        val withSecond = state.addNew(second)

        assertEquals(listOf(first, second), withSecond.tabs)
        assertEquals(second, withSecond.activeTarget)
        assertEquals(first, withSecond.select(first).activeTarget)
    }

    test("materializing a new tab retains its tab position") {
        val first = SessionTabTarget.NewSession(id = 1, ordinal = 1)
        val second = SessionTabTarget.NewSession(id = 2, ordinal = 2)
        val state = SessionTabRegistryState(
            tabs = listOf(first, second),
            activeTarget = second,
        )

        val materialized = state.materialize(second, sessionIndex = 7)

        assertEquals(
            listOf(first, SessionTabTarget.OpenSession(sessionIndex = 7)),
            materialized.tabs,
        )
        assertEquals(SessionTabTarget.OpenSession(sessionIndex = 7), materialized.activeTarget)
    }

    test("closing the active tab selects the nearest remaining tab") {
        val first = SessionTabTarget.NewSession(id = 1, ordinal = 1)
        val session = SessionTabTarget.OpenSession(sessionIndex = 4)
        val second = SessionTabTarget.NewSession(id = 2, ordinal = 2)
        val state = SessionTabRegistryState(
            tabs = listOf(first, session, second),
            activeTarget = session,
        )

        val closed = requireNotNull(state.close(session))

        assertEquals(listOf(first, second), closed.tabs)
        assertEquals(second, closed.activeTarget)
    }

    test("closing a New session tab removes its draft tab") {
        val first = SessionTabTarget.NewSession(id = 1, ordinal = 1)
        val second = SessionTabTarget.NewSession(id = 2, ordinal = 2)
        val state = SessionTabRegistryState(
            tabs = listOf(first, second),
            activeTarget = second,
        )

        val closed = requireNotNull(state.close(second))

        assertEquals(listOf(first), closed.tabs)
        assertEquals(first, closed.activeTarget)
    }
}
