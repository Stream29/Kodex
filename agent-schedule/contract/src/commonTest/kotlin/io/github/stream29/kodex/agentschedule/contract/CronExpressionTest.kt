@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.stream29.kodex.agentschedule.contract

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.datetime.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

val cronExpressionTest by testSuite {
    test("computes strictly next trigger across step, range, list, and weekday expressions") {
        val utc = TimeZone.UTC
        val shanghai = TimeZone.of("Asia/Shanghai")

        // Every 5 minutes: strictly after exact boundary jumps to next 5-min boundary
        assertEquals(
            Instant.parse("2026-11-15T10:05:00Z"),
            nextCronTriggerAt("*/5 * * * *", Instant.parse("2026-11-15T10:00:00Z"), utc),
        )
        assertEquals(
            Instant.parse("2026-11-15T10:05:00Z"),
            nextCronTriggerAt("*/5 * * * *", Instant.parse("2026-11-15T10:00:01Z"), utc),
        )

        // Specific local hour in Asia/Shanghai (+08:00): 09:30 CST == 01:30 UTC
        assertEquals(
            Instant.parse("2026-11-16T01:30:00Z"),
            nextCronTriggerAt("30 9 * * *", Instant.parse("2026-11-15T02:00:00Z"), shanghai),
        )

        // Weekday 1-5 (Mon-Fri): 2026-11-15 is Sunday => next is Monday 2026-11-16 09:00 UTC
        assertEquals(
            Instant.parse("2026-11-16T09:00:00Z"),
            nextCronTriggerAt("0 9 * * 1-5", Instant.parse("2026-11-15T08:00:00Z"), utc),
        )

        // Both 0 and 7 mean Sunday: 2026-11-15 is Sunday
        assertEquals(
            Instant.parse("2026-11-15T12:00:00Z"),
            nextCronTriggerAt("0 12 * * 0", Instant.parse("2026-11-15T08:00:00Z"), utc),
        )
        assertEquals(
            Instant.parse("2026-11-15T12:00:00Z"),
            nextCronTriggerAt("0 12 * * 7", Instant.parse("2026-11-15T08:00:00Z"), utc),
        )

        // Both day-of-month AND day-of-week restricted => OR semantics
        // 2026-11-16 is Monday (dow=1), 2026-11-20 is 20th -> Monday 16th comes first!
        assertEquals(
            Instant.parse("2026-11-16T00:00:00Z"),
            nextCronTriggerAt("0 0 20 * MON", Instant.parse("2026-11-15T12:00:00Z"), utc),
        )

        // Stepped wildcard day field (`*/2`) preserves wildcard AND semantics per Vixie cron:
        // 2026-11-16 is Monday (even day 16), 2026-11-23 is the next Monday on an odd day-of-month (23).
        assertEquals(
            Instant.parse("2026-11-23T00:00:00Z"),
            nextCronTriggerAt("0 0 */2 * MON", Instant.parse("2026-11-15T12:00:00Z"), utc),
        )
        assertEquals(
            Instant.parse("2026-11-16T00:00:00Z"),
            nextCronTriggerAt("0 0 1-31/2 * MON", Instant.parse("2026-11-15T12:00:00Z"), utc),
        )

        // Leap day Feb 29
        assertEquals(
            Instant.parse("2028-02-29T00:00:00Z"),
            nextCronTriggerAt("0 0 29 2 *", Instant.parse("2026-11-15T00:00:00Z"), utc),
        )
    }

    test("computes both occurrences across 1-hour and 30-minute DST fallback overlaps") {
        // America/New_York 2026-11-01: 01:30 EDT (-04:00 -> 05:30Z) repeats at 01:30 EST (-05:00 -> 06:30Z)
        val newYork = TimeZone.of("America/New_York")
        val firstNy = Instant.parse("2026-11-01T05:30:00Z")
        val secondNy = Instant.parse("2026-11-01T06:30:00Z")
        assertEquals(
            firstNy,
            nextCronTriggerAt("30 1 * * *", Instant.parse("2026-11-01T05:00:00Z"), newYork),
        )
        assertEquals(
            secondNy,
            nextCronTriggerAt("30 1 * * *", firstNy, newYork),
        )

        // Australia/Lord_Howe 2026-04-05: 02:00 (+11:00) falls back 30 minutes to 01:30 (+10:30).
        // 01:45 occurs twice: 2026-04-04T14:45:00Z (+11:00) and 2026-04-04T15:15:00Z (+10:30).
        val lordHowe = TimeZone.of("Australia/Lord_Howe")
        val firstLh = Instant.parse("2026-04-04T14:45:00Z")
        val secondLh = Instant.parse("2026-04-04T15:15:00Z")
        assertEquals(
            firstLh,
            nextCronTriggerAt("45 1 * * *", Instant.parse("2026-04-04T14:30:00Z"), lordHowe),
        )
        assertEquals(
            secondLh,
            nextCronTriggerAt("45 1 * * *", firstLh, lordHowe),
        )
    }

    test("rejects malformed or impossible 5-field cron expressions") {
        val base = Instant.parse("2026-11-15T00:00:00Z")
        for (invalid in listOf(
            "",
            "* * * *",
            "0 * * * * *",
            "60 * * * *",
            "* 24 * * *",
            "* * 0 * *",
            "* * 32 * *",
            "* * * 13 *",
            "* * * * 8",
            "*/0 * * * *",
            "10-5 * * * *",
            "0 0 31 2 *",
        )) {
            assertFailsWith<IllegalArgumentException>("Expected failure for '$invalid'") {
                nextCronTriggerAt(invalid, base, TimeZone.UTC)
            }
        }
    }
}
