package io.github.stream29.kodex.cli.sessioncatalog

import io.github.stream29.kodex.cli.components.RunningIndicatorFrames

import com.jakewharton.mosaic.text.AnnotatedString
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.TextStyle
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.cli.components.ellipsizeToTerminalWidth
import io.github.stream29.kodex.utils.terminaltext.terminalCellWidth
import kotlin.time.Clock
import kotlin.time.Instant

/** Sampled catalog label with backend running marker, bounded title and dim relative-time suffix. */
public fun SessionCatalogEntry.sessionBrowserLabel(
    maximumColumns: Int,
    now: Instant = Clock.System.now(),
    runningFrame: String = RunningIndicatorFrames.first(),
): AnnotatedString {
    val name = threadName ?: "Session $sessionIndex"
    val title = if (running) runningFrame + name else name
    val lastActivity = updatedAt?.relativeTimeFrom(now)
        ?: return buildAnnotatedString { append(title.ellipsizeToTerminalWidth(maximumColumns)) }
    val suffix = " $lastActivity"
    val titleColumns = maximumColumns - suffix.terminalCellWidth()
    val displayedTitle = if (titleColumns > 0) {
        title.ellipsizeToTerminalWidth(titleColumns)
    } else {
        (title + suffix).ellipsizeToTerminalWidth(maximumColumns)
    }
    return buildAnnotatedString {
        append(displayedTitle)
        if (titleColumns > 0) withStyle(SpanStyle(textStyle = TextStyle.Dim)) { append(suffix) }
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
