package io.github.stream29.codex.lite.agentstorage.cleanmodels

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stable clean marker for a plan update.
 *
 * The actual plan snapshot is stored in the raw storage settings timeline at
 * the same state index. This clean event only marks that consumers should look
 * up that settings entry when rendering the corresponding turn.
 */
@Serializable
@SerialName("plan_update")
public data object StablePlanUpdate : StableCleanEvent
