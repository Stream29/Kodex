package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import kotlinx.coroutines.flow.StateFlow

/**
 * Read dependencies for one fixed Agent's History, including its multiple storage timelines.
 *
 * [storage] supplies index, work, unstable events and timestamps; [latestIndex] is the committed
 * storage cursor, not a count of visible rows. [state] projects pending/streaming/external-write
 * activity of the same Agent. [cacheNonce], when supplied by remote storage, is the authoritative
 * destructive-replacement generation even when the cursor is unchanged. Without it the History
 * implementation uses its local external-write invalidation generation.
 *
 * All inputs are borrowed. History may cancel its own reads but never closes storage, mutates the
 * Agent, or substitutes the currently selected Agent for this captured source. Constructor and
 * viewport entry do not create a second storage cache or a second History ViewModel.
 */
public data class AgentHistorySource(
    public val storage: KodexAgentStorage,
    public val latestIndex: StateFlow<Int>,
    public val state: StateFlow<KodexAgentStateValue>,
    public val cacheNonce: StateFlow<Long>? = null,
)
