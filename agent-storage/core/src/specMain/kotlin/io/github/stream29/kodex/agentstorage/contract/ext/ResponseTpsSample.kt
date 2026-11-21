package io.github.stream29.kodex.agentstorage.contract.ext

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.LlmRequestMeta
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.getCommitAtOrBefore

/** The quantities for one completed response's estimated token rate. Zero output is a valid sample. */
public data class ResponseTpsSample(
    public val elapsedNanoseconds: Long,
    public val outputTokens: Long,
)

/** Reads only this Agent's latest measured response at or before the inclusive [visibleIndex]. */
public suspend fun KodexAgentStorage.latestResponseTpsSampleAt(visibleIndex: Int): ResponseTpsSample? =
    (getCommitAtOrBefore(visibleIndex, setOf(AgentCommitType.LlmRequestMetaResponse))?.commit as? LlmRequestMeta.Response)
        ?.let { ResponseTpsSample(it.elapsedNanoseconds, it.outputTokens) }
