package io.github.stream29.kodex.app.sessiontabbar.contract

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

val sessionTabBarContractTest by de.infix.testBalloon.framework.core.testSuite {
    test("empty tabs and null selection represent the singleton New Session") {
        assertEquals(null, SessionTabBarState(emptyList(), null).selected)
        val persisted = SessionTabPresentation(SessionTabIdentity("persisted"), "Persisted")
        assertEquals(null, SessionTabBarState(listOf(persisted), null).selected)
        assertFailsWith<IllegalArgumentException> {
            SessionTabBarState(emptyList(), persisted.identity)
        }
    }
    test("state preserves supplied ordering and exact selected identity") {
        val first = SessionTabPresentation(SessionTabIdentity("first"), "First")
        val second = SessionTabPresentation(SessionTabIdentity("second"), "Second", running = true)
        val state = SessionTabBarState(listOf(first, second), second.identity)
        assertEquals(listOf(first, second), state.tabs)
        assertEquals(second.identity, state.selected)
        assertFailsWith<IllegalArgumentException> {
            SessionTabBarState(listOf(first, first), first.identity)
        }
    }

    test("selected identity must be admitted by the immutable snapshot") {
        val tab = SessionTabPresentation(SessionTabIdentity("only"), "Only")
        assertFailsWith<IllegalArgumentException> {
            SessionTabBarState(listOf(tab), SessionTabIdentity("replacement"))
        }
        assertFailsWith<IllegalArgumentException> {
            SessionTabPresentation(SessionTabIdentity("blank-label"), "")
        }
    }
}
