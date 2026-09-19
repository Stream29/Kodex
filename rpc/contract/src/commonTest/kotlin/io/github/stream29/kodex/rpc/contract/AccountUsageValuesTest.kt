package io.github.stream29.kodex.rpc.contract

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.openai.accountusage.CodexAccountRateLimit
import io.github.stream29.kodex.openai.accountusage.CodexAccountRateLimitWindow
import io.github.stream29.kodex.openai.accountusage.CodexAccountTokenUsage
import io.github.stream29.kodex.openai.accountusage.CodexAccountTokenUsageDailyBucket
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageSection
import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageSnapshot
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetCredit
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetCredits
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

val accountUsageValuesTest by testSuite {
    val json = Json { encodeDefaults = true }
    val time = Instant.parse("2026-09-10T01:02:03.123456789Z")
    val snapshot = CodexAccountUsageSnapshot(
        rateLimits = listOf(
            CodexAccountRateLimit(
                name = "primary",
                meteredFeature = "codex",
                allowed = true,
                limitReached = false,
                primaryWindow = CodexAccountRateLimitWindow(42, 18_000, 900, time),
                secondaryWindow = CodexAccountRateLimitWindow(100, 604_800, 86_400, time),
            ),
            CodexAccountRateLimit("other", "other-feature", allowed = false, limitReached = true),
        ),
        resetCredits = CodexRateLimitResetCredits(
            availableCount = 2,
            credits = listOf(
                CodexRateLimitResetCredit("b", time, time, "Reset", "Test credit"),
                CodexRateLimitResetCredit("a", null, null),
            ),
        ),
        tokenUsage = CodexAccountTokenUsage(
            lifetimeTokens = 9_007_199_254_740_993L,
            peakDailyTokens = 4_294_967_296L,
            longestRunningTurnSeconds = 900,
            currentStreakDays = 3,
            longestStreakDays = 12,
            dailyUsageBuckets = listOf(
                CodexAccountTokenUsageDailyBucket("2026-09-10", 123),
                CodexAccountTokenUsageDailyBucket("2026-09-09", 456),
            ),
        ),
        fetchedAt = time,
    )

    test("all usage states round trip the original complete snapshot") {
        val states = listOf(
            SettingsAccountUsageState.Unavailable,
            SettingsAccountUsageState.Loading(),
            SettingsAccountUsageState.Loading(snapshot),
            SettingsAccountUsageState.Available(snapshot),
            SettingsAccountUsageState.Failed("Failed"),
            SettingsAccountUsageState.Failed("Failed", snapshot),
            SettingsAccountUsageState.Redeeming(snapshot),
        )
        for (state in states) {
            val encoded = json.encodeToString(SettingsAccountUsageState.serializer(), state)
            assertEquals(state, json.decodeFromString(SettingsAccountUsageState.serializer(), encoded))
        }
    }

    test("redeeming projection has no private attempt or credential fields") {
        val encoded = json.encodeToJsonElement(
            SettingsAccountUsageState.serializer(),
            SettingsAccountUsageState.Redeeming(snapshot),
        ).jsonObject
        assertEquals(setOf("type", "snapshot"), encoded.keys)
        assertEquals(
            setOf("rateLimits", "resetCredits", "tokenUsage", "unavailableSections", "fetchedAt"),
            encoded.getValue("snapshot").jsonObject.keys,
        )
    }

    test("optional sections retain unavailable versus empty values") {
        val snapshots = listOf(
            snapshot.copy(
                rateLimits = emptyList(),
                resetCredits = CodexRateLimitResetCredits(null),
                tokenUsage = null,
                unavailableSections = CodexAccountUsageSection.entries.toSet(),
            ),
            snapshot.copy(
                rateLimits = emptyList(),
                resetCredits = CodexRateLimitResetCredits(0, emptyList()),
                tokenUsage = CodexAccountTokenUsage(dailyUsageBuckets = emptyList()),
            ),
            snapshot.copy(tokenUsage = CodexAccountTokenUsage()),
        )
        for (value in snapshots) {
            val encoded = json.encodeToString(CodexAccountUsageSnapshot.serializer(), value)
            assertEquals(value, json.decodeFromString(CodexAccountUsageSnapshot.serializer(), encoded))
        }
    }

    test("missing optional fields preserve existing defaults") {
        val decoded = json.decodeFromString(
            CodexAccountUsageSnapshot.serializer(),
            """{"rateLimits":[],"resetCredits":{"availableCount":null},"fetchedAt":"$time"}""",
        )
        assertEquals(
            CodexAccountUsageSnapshot(emptyList(), CodexRateLimitResetCredits(null), fetchedAt = time),
            decoded,
        )
        assertEquals(
            SettingsAccountUsageState.Loading(),
            json.decodeFromString(SettingsAccountUsageState.Loading.serializer(), "{}"),
        )
    }

    test("required fields and existing failure validation are not bypassed") {
        assertFailsWith<SerializationException> {
            json.decodeFromString(CodexAccountUsageSnapshot.serializer(), "{}")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString(SettingsAccountUsageState.serializer(), """{"type":"unknown"}""")
        }
        assertFailsWith<IllegalArgumentException> {
            json.decodeFromString(SettingsAccountUsageState.Failed.serializer(), """{"message":" "}""")
        }
    }
}
