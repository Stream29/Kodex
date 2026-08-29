package io.github.stream29.kodex.agentstorage.contract

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.CleanCompactionPoint
import io.github.stream29.kodex.openai.KodexAgentSettings
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Publishes the required snapshot-zero state into an empty AgentStorage.
 *
 * This is the canonical initialization path for a newly created root
 * session. The storage must have no entry in any timeline.
 */
@OptIn(ExperimentalUuidApi::class)
public suspend fun MutableKodexAgentStorage.initialize(initialSettings: KodexAgentSettings) {
    require(latestIndex() < 0) { "AgentStorage is already initialized." }
    val windowId = Uuid.generateV7().toString()
    index.setWithTransaction(
        index = 0,
        value = CleanCompactionPoint(
            windowNumber = 0,
            firstWindowId = windowId,
            windowId = windowId,
        ),
    ) {
        timestamp.setWithTransaction(0, Clock.System.now()) {
            settings.setWithTransaction(0, initialSettings) {
                tokenCount.setWithTransaction(0, 0L) { }
            }
        }
    }
}
