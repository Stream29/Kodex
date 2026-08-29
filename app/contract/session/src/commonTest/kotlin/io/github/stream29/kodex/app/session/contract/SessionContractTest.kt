package io.github.stream29.kodex.app.session.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

public class SessionContractTest {
    @Test
    public fun persistedSummaryDefaultsToAnIdleRootSession() {
        assertEquals(
            PersistedSessionSummaryState(
                rootRunning = false,
                lastActivityAt = null,
                revision = 0,
            ),
            PersistedSessionSummaryState(),
        )
    }

    @Test
    public fun persistedSummaryAcceptsRootActivityAndRevision() {
        val activityAt = Instant.fromEpochSeconds(42)

        assertEquals(
            PersistedSessionSummaryState(
                rootRunning = true,
                lastActivityAt = activityAt,
                revision = 3,
            ),
            PersistedSessionSummaryState(
                rootRunning = true,
                lastActivityAt = activityAt,
                revision = 3,
            ),
        )
    }

    @Test
    public fun persistedSummaryRejectsNegativeRevision() {
        assertFailsWith<IllegalArgumentException> {
            PersistedSessionSummaryState(revision = -1)
        }
    }
}
