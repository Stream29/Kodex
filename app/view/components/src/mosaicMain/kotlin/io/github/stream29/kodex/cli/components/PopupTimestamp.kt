package io.github.stream29.kodex.cli.components

import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Shared visual formatting only; acquiring/invalidating a timestamp belongs to its state owner. */
public fun formatPopupTimestamp(timestamp: Instant, timeZone: TimeZone): String {
    val local = timestamp.toLocalDateTime(timeZone)
    val dateTime = "${local.year.toString().padStart(4, '0')}-" +
        "${(local.month.ordinal + 1).toString().padStart(2, '0')}-" +
        "${local.day.toString().padStart(2, '0')} " +
        "${local.hour.toString().padStart(2, '0')}:" +
        "${local.minute.toString().padStart(2, '0')}:" +
        local.second.toString().padStart(2, '0')
    val offset = timeZone.offsetAt(timestamp).toString().let { if (it == "Z") "+00:00" else it }
    return "$dateTime UTC$offset"
}
