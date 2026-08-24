package io.github.stream29.kodex.cli.settings

import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.openai.accountusage.CodexAccountRateLimit
import io.github.stream29.kodex.openai.accountusage.CodexAccountRateLimitWindow
import io.github.stream29.kodex.openai.accountusage.CodexAccountTokenUsage
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageSnapshot
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetCredits
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class CodexAccountUsageSettingsTest {
    @Test
    fun availableUsageRendersWindowsTokensAndActions() = runTest {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(100)) {
                    CodexAccountUsageSettingsContent(
                        state = SettingsAccountUsageState.Available(accountUsageSnapshot()),
                        onRefresh = {},
                        onUseReset = {},
                    )
                }
            }

            assertTrue(
                "Codex: 5h 42% used (resets in 15m) 7d 10% used (resets in 2d)" in snapshot,
                snapshot,
            )
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
                        state = SettingsAccountUsageState.Unavailable,
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
                secondaryWindow = CodexAccountRateLimitWindow(
                    usedPercent = 10,
                    durationSeconds = 604_800,
                    resetAfterSeconds = 172_800,
                    resetsAt = Instant.parse("2026-08-10T01:00:00Z"),
                ),
            ),
        ),
        resetCredits = CodexRateLimitResetCredits(availableCount = 2),
        tokenUsage = CodexAccountTokenUsage(lifetimeTokens = 123_456),
        fetchedAt = Instant.parse("2026-08-08T01:00:00Z"),
    )
