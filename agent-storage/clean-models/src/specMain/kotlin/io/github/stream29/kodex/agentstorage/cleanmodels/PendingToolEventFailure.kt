package io.github.stream29.kodex.agentstorage.cleanmodels

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolEvent

/** Converts one pending local call into its matching durable failed tool event. */
public expect fun PendingToolEvent.toFailedToolEvent(message: String): StableCleanEvent.CompletedTool
