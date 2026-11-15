@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.stream29.kodex.agentschedule.contract

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.offsetAt
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

public actual fun nextCronTriggerAt(
    cron: String,
    after: Instant,
    timeZone: TimeZone,
): Instant {
    val parsed = parseCronExpression(cron)
    val startDate = after.toLocalDateTime(timeZone).date.minus(1, DateTimeUnit.DAY)
    val maxYear = startDate.year + 8

    var date = startDate
    var bestCandidate: Instant? = null

    while (date.year <= maxYear) {
        if (bestCandidate != null) {
            val dayFloor = LocalDateTime(date.year, date.month, date.day, 0, 0, 0, 0)
                .toInstant(timeZone) - 3.hours
            if (dayFloor > bestCandidate) {
                return bestCandidate
            }
        }

        if (date.month.number !in parsed.months) {
            date = if (date.month.number == 12) {
                LocalDate(date.year + 1, 1, 1)
            } else {
                LocalDate(date.year, date.month.number + 1, 1)
            }
            continue
        }

        if (!parsed.matchesDate(date)) {
            date = date.plus(1, DateTimeUnit.DAY)
            continue
        }

        for (hour in parsed.hours) {
            for (minute in parsed.minutes) {
                val targetLocal = LocalDateTime(
                    year = date.year,
                    month = date.month,
                    day = date.day,
                    hour = hour,
                    minute = minute,
                    second = 0,
                    nanosecond = 0,
                )
                val baseInstant = targetLocal.toInstant(timeZone)
                val earlyOffsetSeconds = timeZone.offsetAt(baseInstant).totalSeconds
                val laterOffsetSeconds = timeZone.offsetAt(baseInstant + 3.hours).totalSeconds
                val overlapShiftSeconds = (earlyOffsetSeconds - laterOffsetSeconds).coerceAtLeast(0)

                for (pass in 0..1) {
                    if (pass == 1 && overlapShiftSeconds == 0) continue
                    val candidate = if (pass == 0) baseInstant else baseInstant + overlapShiftSeconds.seconds
                    if (candidate <= after) continue
                    if (candidate.toLocalDateTime(timeZone) == targetLocal) {
                        if (bestCandidate == null || candidate < bestCandidate) {
                            bestCandidate = candidate
                        }
                    }
                }
            }
        }

        date = date.plus(1, DateTimeUnit.DAY)
    }

    return bestCandidate
        ?: throw IllegalArgumentException("Cron expression '$cron' produces no valid trigger within 8 years.")
}

private class ParsedCronExpression(
    val minutes: IntArray,
    val hours: IntArray,
    val daysOfMonth: Set<Int>,
    val dayOfMonthRestricted: Boolean,
    val months: Set<Int>,
    val daysOfWeekIso: Set<Int>,
    val dayOfWeekRestricted: Boolean,
) {
    fun matchesDate(date: LocalDate): Boolean {
        val domMatches = date.day in daysOfMonth
        val dowMatches = date.dayOfWeek.isoDayNumber in daysOfWeekIso
        return if (dayOfMonthRestricted && dayOfWeekRestricted) {
            domMatches || dowMatches
        } else {
            domMatches && dowMatches
        }
    }
}

private val MonthNames: Map<String, Int> = mapOf(
    "JAN" to 1, "FEB" to 2, "MAR" to 3, "APR" to 4,
    "MAY" to 5, "JUN" to 6, "JUL" to 7, "AUG" to 8,
    "SEP" to 9, "OCT" to 10, "NOV" to 11, "DEC" to 12,
)

private val DayOfWeekNames: Map<String, Int> = mapOf(
    "SUN" to 0,
    "MON" to 1,
    "TUE" to 2,
    "WED" to 3,
    "THU" to 4,
    "FRI" to 5,
    "SAT" to 6,
)

private fun parseCronExpression(cron: String): ParsedCronExpression {
    val trimmed = cron.trim()
    require(trimmed.isNotEmpty()) { "Cron expression must not be blank." }
    val fields = trimmed.split(Regex("\\s+"))
    require(fields.size == 5) {
        "Invalid 5-field cron expression '$cron': expected 5 fields (minute hour day-of-month month day-of-week), got ${fields.size}."
    }

    val (minutesSet, _) = parseCronField(fields[0], min = 0, max = 59, aliases = emptyMap(), fieldName = "minute")
    val (hoursSet, _) = parseCronField(fields[1], min = 0, max = 23, aliases = emptyMap(), fieldName = "hour")
    val (domSet, domRestricted) = parseCronField(fields[2], min = 1, max = 31, aliases = emptyMap(), fieldName = "day-of-month")
    val (monthsSet, _) = parseCronField(fields[3], min = 1, max = 12, aliases = MonthNames, fieldName = "month")
    val (rawDowSet, dowRestricted) = parseCronField(fields[4], min = 0, max = 7, aliases = DayOfWeekNames, fieldName = "day-of-week")

    val isoDowSet = rawDowSet.mapTo(mutableSetOf()) { value ->
        if (value == 0 || value == 7) DayOfWeek.SUNDAY.isoDayNumber else value
    }

    return ParsedCronExpression(
        minutes = minutesSet.sorted().toIntArray(),
        hours = hoursSet.sorted().toIntArray(),
        daysOfMonth = domSet,
        dayOfMonthRestricted = domRestricted,
        months = monthsSet,
        daysOfWeekIso = isoDowSet,
        dayOfWeekRestricted = dowRestricted,
    )
}

private fun parseCronField(
    rawField: String,
    min: Int,
    max: Int,
    aliases: Map<String, Int>,
    fieldName: String,
): Pair<Set<Int>, Boolean> {
    require(rawField.isNotEmpty()) { "Cron $fieldName field must not be empty." }
    if (rawField == "*" || rawField == "*/1") {
        return (min..max).toSet() to false
    }

    val values = mutableSetOf<Int>()
    val parts = rawField.split(',')
    for (part in parts) {
        require(part.isNotEmpty()) { "Empty list element in cron $fieldName field '$rawField'." }
        val stepSplit = part.split('/')
        require(stepSplit.size <= 2) { "Invalid step syntax '$part' in cron $fieldName field." }
        val base = stepSplit[0]
        val step = if (stepSplit.size == 2) {
            val parsedStep = stepSplit[1].toIntOrNull()
                ?: throw IllegalArgumentException("Invalid step '${stepSplit[1]}' in cron $fieldName field.")
            require(parsedStep >= 1) { "Cron $fieldName step must be >= 1, got $parsedStep." }
            parsedStep
        } else {
            null
        }

        when {
            base == "*" -> {
                val stride = step ?: 1
                for (v in min..max step stride) {
                    values += v
                }
            }
            '-' in base -> {
                val bounds = base.split('-')
                require(bounds.size == 2 && bounds[0].isNotEmpty() && bounds[1].isNotEmpty()) {
                    "Invalid range '$base' in cron $fieldName field."
                }
                val start = parseCronAtom(bounds[0], min, max, aliases, fieldName)
                val end = parseCronAtom(bounds[1], min, max, aliases, fieldName)
                require(start <= end) {
                    "Cron $fieldName range start $start must not exceed end $end in '$part'."
                }
                val stride = step ?: 1
                for (v in start..end step stride) {
                    values += v
                }
            }
            else -> {
                val single = parseCronAtom(base, min, max, aliases, fieldName)
                if (step != null) {
                    for (v in single..max step step) {
                        values += v
                    }
                } else {
                    values += single
                }
            }
        }
    }

    require(values.isNotEmpty()) { "Cron $fieldName field '$rawField' matched no values." }
    return values to !rawField.startsWith('*')
}

private fun parseCronAtom(
    token: String,
    min: Int,
    max: Int,
    aliases: Map<String, Int>,
    fieldName: String,
): Int {
    val value = aliases[token.uppercase()] ?: token.toIntOrNull()
        ?: throw IllegalArgumentException("Invalid value '$token' in cron $fieldName field.")
    require(value in min..max) {
        "Value $value out of range $min..$max in cron $fieldName field."
    }
    return value
}
