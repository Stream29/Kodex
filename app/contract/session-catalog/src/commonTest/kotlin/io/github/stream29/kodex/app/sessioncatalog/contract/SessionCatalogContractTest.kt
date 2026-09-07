package io.github.stream29.kodex.app.sessioncatalog.contract

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

val sessionCatalogContractTest by testSuite {
    test("entryKeepsPersistedIdentityAndValidatesDisplayName") {
        val entry = SessionCatalogEntry(
            sessionIndex = 3,
            threadName = "Thread",
        )

        assertEquals(3, entry.sessionIndex)
        assertEquals("Thread", entry.threadName)
        assertFailsWith<IllegalArgumentException> {
            entry.copy(sessionIndex = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            entry.copy(threadName = " ")
        }
    }
}
