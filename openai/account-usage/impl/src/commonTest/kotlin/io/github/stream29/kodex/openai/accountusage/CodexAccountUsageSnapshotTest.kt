package io.github.stream29.kodex.openai.accountusage

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.CodexAccountRateLimitStatus
import io.github.stream29.kodex.openai.CodexAccountUsageResponse
import io.github.stream29.kodex.openai.CodexRateLimitResetCredit
import io.github.stream29.kodex.openai.CodexRateLimitResetCreditsResponse
import io.github.stream29.kodex.openai.CodexRateLimitResetCreditsSummary
import io.github.stream29.kodex.openai.CodexRateLimitWindow
import io.github.stream29.kodex.openai.CodexTokenUsageProfile
import io.github.stream29.kodex.openai.CodexTokenUsageProfileStats
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Instant

val codexAccountUsageSnapshotTest by testSuite {
    test("maps rate limits, available credits, and token usage") {
        val fetchedAt = Instant.parse("2026-08-08T01:00:00Z")
        val snapshot = buildAccountUsageSnapshot(
            usage = CodexAccountUsageResponse(
                rateLimit = CodexAccountRateLimitStatus(
                    allowed = true,
                    limitReached = false,
                    primaryWindow = CodexRateLimitWindow(
                        usedPercent = 42,
                        limitWindowSeconds = 18_000,
                        resetAfterSeconds = 900,
                        resetAt = 1_786_165_200,
                    ),
                ),
                rateLimitResetCredits = CodexRateLimitResetCreditsSummary(availableCount = 3),
            ),
            resetCredits = CodexRateLimitResetCreditsResponse(
                credits = listOf(
                    CodexRateLimitResetCredit(
                        id = "later",
                        resetType = "codex_rate_limits",
                        status = "available",
                        grantedAt = "2026-08-08T00:00:00Z",
                        expiresAt = "2026-08-10T00:00:00Z",
                    ),
                    CodexRateLimitResetCredit(
                        id = "used",
                        resetType = "codex_rate_limits",
                        status = "redeemed",
                        grantedAt = "2026-08-08T00:00:00Z",
                    ),
                    CodexRateLimitResetCredit(
                        id = "earlier",
                        resetType = "codex_rate_limits",
                        status = "available",
                        grantedAt = "invalid",
                        expiresAt = "2026-08-09T00:00:00Z",
                        title = " Full reset ",
                    ),
                ),
                availableCount = 2,
            ),
            tokenUsage = CodexTokenUsageProfile(
                CodexTokenUsageProfileStats(lifetimeTokens = 123_456),
            ),
            fetchedAt = fetchedAt,
        )

        assertEquals(42L, snapshot.rateLimits.single().primaryWindow?.usedPercent)
        assertEquals(2L, snapshot.resetCredits.availableCount)
        assertEquals(listOf("earlier", "later"), snapshot.resetCredits.credits?.map { it.id })
        assertNull(snapshot.resetCredits.credits?.first()?.grantedAt)
        assertEquals("Full reset", snapshot.resetCredits.credits?.first()?.title)
        assertEquals(123_456L, snapshot.tokenUsage?.lifetimeTokens)
        assertEquals(fetchedAt, snapshot.fetchedAt)
    }

    test("falls back to summary count when optional sections are unavailable") {
        val snapshot = buildAccountUsageSnapshot(
            usage = CodexAccountUsageResponse(
                rateLimitResetCredits = CodexRateLimitResetCreditsSummary(availableCount = 4),
            ),
            resetCredits = null,
            tokenUsage = null,
            unavailableSections = setOf(
                CodexAccountUsageSection.ResetCreditDetails,
                CodexAccountUsageSection.TokenUsage,
            ),
            fetchedAt = Instant.parse("2026-08-08T01:00:00Z"),
        )

        assertEquals(4L, snapshot.resetCredits.availableCount)
        assertNull(snapshot.resetCredits.credits)
        assertNull(snapshot.tokenUsage)
        assertEquals(
            setOf(
                CodexAccountUsageSection.ResetCreditDetails,
                CodexAccountUsageSection.TokenUsage,
            ),
            snapshot.unavailableSections,
        )
    }

    test("usage states retain only their explicitly provided snapshot") {
        val snapshot = buildAccountUsageSnapshot(
            usage = CodexAccountUsageResponse(),
            resetCredits = null,
            tokenUsage = null,
            fetchedAt = Instant.parse("2026-08-08T01:00:00Z"),
        )

        assertSame(snapshot, CodexAccountUsageState.Available(snapshot).snapshotOrNull())
        assertSame(snapshot, CodexAccountUsageState.Loading(snapshot).snapshotOrNull())
        assertSame(snapshot, CodexAccountUsageState.Failed("failure", snapshot).snapshotOrNull())
        assertSame(
            snapshot,
            CodexAccountUsageState.Redeeming(
                snapshot,
                CodexRateLimitResetAttempt("attempt"),
            ).snapshotOrNull(),
        )
        assertNull(CodexAccountUsageState.Unavailable("unavailable").snapshotOrNull())
    }
}
