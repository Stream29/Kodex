package io.github.stream29.kodex.openai

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFalse

val codexAccountUsageModelsSerializationTest by testSuite {
    test("decodes usage windows and reset-credit summary") {
        val response = OpenAiJsonCodec.decodeFromString<CodexAccountUsageResponse>(
            """
            {
              "plan_type": "pro",
              "rate_limit": {
                "allowed": true,
                "limit_reached": false,
                "primary_window": {
                  "used_percent": 42,
                  "limit_window_seconds": 18000,
                  "reset_after_seconds": 900,
                  "reset_at": 1786165200
                },
                "secondary_window": null
              },
              "additional_rate_limits": [{
                "limit_name": "Review",
                "metered_feature": "codex_review",
                "rate_limit": null
              }],
              "rate_limit_reset_credits": {"available_count": 2}
            }
            """.trimIndent(),
        )

        assertEquals("pro", response.planType)
        assertEquals(42L, response.rateLimit?.primaryWindow?.usedPercent)
        assertEquals("codex_review", response.additionalRateLimits?.single()?.meteredFeature)
        assertEquals(2L, response.rateLimitResetCredits?.availableCount)
    }

    test("decodes reset-credit details and token activity") {
        val credits = OpenAiJsonCodec.decodeFromString<CodexRateLimitResetCreditsResponse>(
            """
            {
              "credits": [{
                "id": "credit-1",
                "reset_type": "codex_rate_limits",
                "status": "available",
                "granted_at": "2026-08-08T00:00:00Z",
                "expires_at": "2026-08-09T00:00:00Z",
                "title": "Full reset",
                "description": "Reset current usage limits."
              }],
              "available_count": 1
            }
            """.trimIndent(),
        )
        val profile = OpenAiJsonCodec.decodeFromString<CodexTokenUsageProfile>(
            """
            {
              "stats": {
                "lifetime_tokens": 123456,
                "peak_daily_tokens": 7890,
                "daily_usage_buckets": [
                  {"start_date": "2026-08-08", "tokens": 321}
                ]
              }
            }
            """.trimIndent(),
        )

        assertEquals("credit-1", credits.credits.single().id)
        assertEquals(123456L, profile.stats.lifetimeTokens)
        assertEquals(321L, profile.stats.dailyUsageBuckets?.single()?.tokens)
    }

    test("omits an unspecified credit id and decodes consume outcome") {
        val encoded = OpenAiJsonCodec.parseToJsonElement(
            OpenAiJsonCodec.encodeToString(
                CodexRateLimitResetConsumeRequest(redeemRequestId = "attempt-1"),
            ),
        ).jsonObject
        val response = OpenAiJsonCodec.decodeFromString<CodexRateLimitResetConsumeResponse>(
            """{"code":"already_redeemed","windows_reset":2}""",
        )

        assertEquals("attempt-1", encoded.getValue("redeem_request_id").jsonPrimitive.content)
        assertFalse("credit_id" in encoded)
        assertEquals(CodexRateLimitResetConsumeCode.AlreadyRedeemed, response.code)
        assertEquals(2L, response.windowsReset)
    }
}
