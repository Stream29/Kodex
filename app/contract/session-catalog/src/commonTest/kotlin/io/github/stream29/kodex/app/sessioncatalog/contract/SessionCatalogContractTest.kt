package io.github.stream29.kodex.app.sessioncatalog.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

public class SessionCatalogContractTest {
    @Test
    public fun entryKeepsPersistedIdentityAndValidatesDisplayName() {
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
