package io.github.stream29.kodex.agentstorage.contract.ext

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CommitTimestamp
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.TokenCountSnapshot
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Publishes the required snapshot-zero state into an empty AgentStorage. */
@OptIn(ExperimentalUuidApi::class)
public actual suspend fun MutableKodexAgentStorage.initialize(initialSettings: KodexAgentSettings) {
    require(latestIndex() < 0) { "AgentStorage is already initialized." }
    val windowId = Uuid.generateV7().toString()
    val turnId = Uuid.generateV7().toString()
    val initialTimestamp = Clock.System.now()
    val committedIndex = commit(
        listOf(
            CommitTimestamp(initialTimestamp),
            initialSettings.copy(
                turnId = turnId,
                windowNumber = 0,
                firstWindowId = windowId,
                previousWindowId = null,
                windowId = windowId,
            ),
            TokenCountSnapshot.Initialization(0L),
        ),
    )
    check(committedIndex == 0) { "Initialized storage must start at index 0, got $committedIndex." }
}
