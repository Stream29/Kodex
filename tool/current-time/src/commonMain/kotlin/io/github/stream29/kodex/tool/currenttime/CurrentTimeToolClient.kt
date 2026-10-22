@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.tool.currenttime

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/** Local current-time source used by `clock.curr_time`. */
public actual class CurrentTimeToolClient actual constructor(
    private val clock: Clock,
) {
    public actual fun currentTime(): String =
        clock.now().formatCurrentTimeUtc()
}

internal fun Instant.formatCurrentTimeUtc(): String {
    val dateTime = toLocalDateTime(TimeZone.UTC)
    return "${currentTimeFormat.format(dateTime)} UTC"
}

private val currentTimeFormat = LocalDateTime.Format {
    year()
    chars("-")
    monthNumber()
    chars("-")
    day()
    chars(" ")
    hour()
    chars(":")
    minute()
    chars(":")
    second()
}
