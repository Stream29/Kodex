package io.github.stream29.kodex.agentstorage.contract.ext

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.CleanCompactionPoint
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.openai.KodexAgentSettings
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Publishes the required snapshot-zero state into an empty AgentStorage. */
@OptIn(ExperimentalUuidApi::class)
public suspend fun MutableKodexAgentStorage.initialize(initialSettings: KodexAgentSettings) {
    require(latestIndex() < 0) { "AgentStorage is already initialized." }
    val windowId = Uuid.generateV7().toString()
    val turnId = Uuid.generateV7().toString()
    val initialTimestamp = Clock.System.now()
    index[0] = CleanCompactionPoint
    timestamp[0] = initialTimestamp
    settings[0] = initialSettings.copy(
        turnId = turnId,
        windowNumber = 0,
        firstWindowId = windowId,
        previousWindowId = null,
        windowId = windowId,
    )
    tokenCount[0] = 0L
}
