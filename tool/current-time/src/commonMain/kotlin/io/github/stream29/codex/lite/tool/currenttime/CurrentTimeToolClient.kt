@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.stream29.codex.lite.tool.currenttime

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/** Local current-time source used by `clock.curr_time`. */
public class CurrentTimeToolClient(
    private val clock: Clock = Clock.System,
) {
    public fun currentTime(): String =
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
