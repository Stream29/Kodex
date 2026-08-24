package io.github.stream29.kodex.cli.app

import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.cli.components.ellipsizeToTerminalWidth
import io.github.stream29.kodex.utils.terminaltext.terminalCellWidth
import com.jakewharton.mosaic.text.AnnotatedString
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.TextStyle
import kotlin.time.Clock
import kotlin.time.Instant

internal fun SessionCatalogEntry.sessionBrowserLabel(
    maximumColumns: Int,
    now: Instant = Clock.System.now(),
): AnnotatedString {
    val title = threadName ?: "Session $sessionIndex"
    val lastActivity = lastActivityAt?.relativeTimeFrom(now)
        ?: return buildAnnotatedString {
            append(title.ellipsizeToTerminalWidth(maximumColumns))
        }
    val suffix = " $lastActivity"
    val titleColumns = maximumColumns - suffix.terminalCellWidth()
    val displayedTitle = if (titleColumns > 0) {
        title.ellipsizeToTerminalWidth(titleColumns)
    } else {
        (title + suffix).ellipsizeToTerminalWidth(maximumColumns)
    }
    if (titleColumns <= 0) {
        return buildAnnotatedString {
            append(displayedTitle)
        }
    }
    return buildAnnotatedString {
        append(displayedTitle)
        withStyle(SpanStyle(textStyle = TextStyle.Dim)) {
            append(suffix)
        }
    }
}

private fun Instant.relativeTimeFrom(now: Instant): String {
    val seconds = (now - this).inWholeSeconds.coerceAtLeast(0L)
    return when {
        seconds < 60L -> "now"
        seconds < 60L * 60L -> "${seconds / 60L}m ago"
        seconds < 24L * 60L * 60L -> "${seconds / (60L * 60L)}h ago"
        else -> "${seconds / (24L * 60L * 60L)}d ago"
    }
}
