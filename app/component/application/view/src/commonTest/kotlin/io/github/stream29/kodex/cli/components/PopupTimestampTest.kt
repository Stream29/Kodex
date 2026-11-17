package io.github.stream29.kodex.cli.components

import de.infix.testBalloon.framework.core.testSuite
import kotlinx.datetime.TimeZone
import kotlin.test.assertEquals
import kotlin.time.Instant

val portablePopupTimestampTest by testSuite {
    test("local seconds preserve explicit offsets across a date boundary") {
        val value = Instant.parse("2026-09-08T01:02:03.456Z")
        assertEquals("2026-09-08 09:02:03 UTC+08:00", formatPopupTimestamp(value, TimeZone.of("UTC+08:00")))
        assertEquals("2026-09-08 01:02:03 UTC+00:00", formatPopupTimestamp(value, TimeZone.UTC))
        assertEquals("2026-09-07 21:02:03 UTC-04:00", formatPopupTimestamp(value, TimeZone.of("UTC-04:00")))
        assertEquals(
            "2026-01-07 20:02:03 UTC-05:00",
            formatPopupTimestamp(Instant.parse("2026-01-08T01:02:03Z"), TimeZone.of("UTC-05:00")),
        )
    }
    test("fractional seconds are omitted while non-hour offsets remain exact") {
        assertEquals(
            "2026-09-08 06:47:03 UTC+05:45",
            formatPopupTimestamp(Instant.parse("2026-09-08T01:02:03.999Z"), TimeZone.of("UTC+05:45")),
        )
    }
}
