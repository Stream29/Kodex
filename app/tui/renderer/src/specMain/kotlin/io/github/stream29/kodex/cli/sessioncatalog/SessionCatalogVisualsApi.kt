package io.github.stream29.kodex.cli.sessioncatalog

import com.jakewharton.mosaic.text.AnnotatedString
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.cli.components.RunningIndicatorFrames
import kotlin.time.Clock
import kotlin.time.Instant

/** Sampled catalog label with backend running marker, bounded title and dim relative-time suffix. */
public expect fun SessionCatalogEntry.sessionBrowserLabel(
    maximumColumns: Int,
    now: Instant = Clock.System.now(),
    runningFrame: String = RunningIndicatorFrames.first(),
): AnnotatedString
