package io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable

import io.github.stream29.codex.lite.agentstorage.cleanmodels.CleanOpenAiEvent
import kotlinx.serialization.Serializable

/**
 * Durable clean event in the unfinished tool tail.
 *
 * Unlike stable events, an unstable event is removed when the matching tool
 * interaction becomes complete. Some hosted calls have no client-executable
 * `call_id`, so they share this root without being [PendingToolEvent]s.
 */
@Serializable
public sealed interface UnstableCleanEvent : CleanOpenAiEvent
