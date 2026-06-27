package io.github.stream29.codex.lite.agentstorage.cleanmodels

import kotlinx.serialization.Serializable

/**
 * Stable clean marker for a plan update.
 *
 * The actual plan snapshot is stored in the raw storage plan timeline at the
 * same history index. This clean event only marks that consumers should look up
 * that timeline entry when rendering the corresponding turn.
 */
@Serializable
public data object StablePlanUpdate
