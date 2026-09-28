package io.github.stream29.kodex.cli.history

import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import kotlinx.coroutines.flow.StateFlow

/** Read-only presentation inputs. No backend scope, mutation API, runtime or remote Job. */
public data class AgentHistorySource(
    public val storage: KodexAgentStorage,
    public val latestIndex: StateFlow<Int>,
    public val state: StateFlow<KodexAgentStateValue>,
    public val cacheNonce: StateFlow<Long>? = null,
)
