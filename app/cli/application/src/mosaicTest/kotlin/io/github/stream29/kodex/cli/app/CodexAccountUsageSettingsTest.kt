package io.github.stream29.kodex.cli.app

import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.openai.accountusage.CodexAccountRateLimit
import io.github.stream29.kodex.openai.accountusage.CodexAccountRateLimitWindow
import io.github.stream29.kodex.openai.accountusage.CodexAccountTokenUsage
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageSnapshot
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageState
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetCredit
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetCredits
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class CodexAccountUsageSettingsTest {
    @Test
    fun availableUsageRendersWindowsTokensAndResetAction() = runTest {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(100)) {
                    CodexAccountUsageSettingsContent(
                        state = CodexAccountUsageState.Available(accountUsageSnapshot()),
                        onRefresh = {},
                        onUseReset = {},
                    )
                }
            }

            assertTrue("Codex: 5h 42% used (resets in 15m)" in snapshot, snapshot)
            assertTrue("Lifetime tokens: 123,456" in snapshot, snapshot)
            assertTrue("Usage limit resets: 2 available" in snapshot, snapshot)
            assertTrue("[Refresh] [Use reset]" in snapshot, snapshot)
        }
    }

    @Test
    fun unavailableUsageRequiresSignInAndHidesActions() = runTest {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(80)) {
                    CodexAccountUsageSettingsContent(
                        state = CodexAccountUsageState.Unavailable("Authentication unavailable."),
                        onRefresh = {},
                        onUseReset = {},
                    )
                }
            }

            assertTrue("Sign in to view Codex usage." in snapshot, snapshot)
            assertFalse("[Refresh]" in snapshot, snapshot)
            assertFalse("[Use reset]" in snapshot, snapshot)
        }
    }

    @Test
    fun resetRequestUsesDetailsOrFallsBackToBackendSelection() {
        val detailed = accountUsageSnapshot().usageResetRequestOrNull()
        val fallback = accountUsageSnapshot().copy(
            resetCredits = CodexRateLimitResetCredits(
                availableCount = 1,
                credits = null,
            ),
        ).usageResetRequestOrNull()
        val unavailable = accountUsageSnapshot().copy(
            resetCredits = CodexRateLimitResetCredits(
                availableCount = 0,
                credits = emptyList(),
            ),
        ).usageResetRequestOrNull()

        assertEquals(listOf("credit-1"), detailed?.options?.map { it.creditId })
        assertEquals("Earned reset", detailed?.options?.single()?.title)
        assertNull(fallback?.options?.single()?.creditId)
        assertNull(unavailable)
    }
}

private fun accountUsageSnapshot(): CodexAccountUsageSnapshot =
    CodexAccountUsageSnapshot(
        rateLimits = listOf(
            CodexAccountRateLimit(
                name = "Codex",
                meteredFeature = "codex",
                allowed = true,
                limitReached = false,
                primaryWindow = CodexAccountRateLimitWindow(
                    usedPercent = 42,
                    durationSeconds = 18_000,
                    resetAfterSeconds = 900,
                    resetsAt = Instant.parse("2026-08-08T02:00:00Z"),
                ),
            ),
        ),
        resetCredits = CodexRateLimitResetCredits(
            availableCount = 2,
            credits = listOf(
                CodexRateLimitResetCredit(
                    id = "credit-1",
                    grantedAt = Instant.parse("2026-08-08T00:00:00Z"),
                    expiresAt = Instant.parse("2026-08-09T00:00:00Z"),
                    title = "Earned reset",
                ),
            ),
        ),
        tokenUsage = CodexAccountTokenUsage(lifetimeTokens = 123_456),
        fetchedAt = Instant.parse("2026-08-08T01:00:00Z"),
    )
