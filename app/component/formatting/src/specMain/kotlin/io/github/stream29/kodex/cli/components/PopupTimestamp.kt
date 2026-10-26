package io.github.stream29.kodex.cli.components

import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/** Shared visual formatting only; acquiring/invalidating a timestamp belongs to its state owner. */
public expect fun formatPopupTimestamp(timestamp: Instant, timeZone: TimeZone): String
