package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSubagentCompletedEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSubagentCreatedEvent
import io.github.stream29.kodex.agentstorage.contract.ObservableKodexAgentStorage
import io.github.stream29.kodex.utils.rpcexception.AgentNotActive
import io.github.stream29.kodex.utils.rpcexception.AgentNotFound
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal sealed interface SubagentReturnAnchorResult {
    data class Found(val index: Int, val cacheNonce: Long) : SubagentReturnAnchorResult
    data object Missing : SubagentReturnAnchorResult
    data object Invalidated : SubagentReturnAnchorResult
    data class ReadFailed(val cause: Throwable) : SubagentReturnAnchorResult
}

/** Resolves a record on the supplied parent only. A caller must revalidate this binding and nonce before scrolling. */
internal suspend fun RpcSessionBinding.findSubagentReturnAnchor(
    childAgentId: Int, type: AgentCommitType,
): SubagentReturnAnchorResult =
    findSubagentReturnAnchor(storage, childAgentId, type) { runCatching { ensureActive() }.isSuccess }

internal suspend fun findSubagentReturnAnchor(
    storage: ObservableKodexAgentStorage, childAgentId: Int, type: AgentCommitType,
    isBound: () -> Boolean,
): SubagentReturnAnchorResult {
    require(childAgentId >= 0)
    require(type == AgentCommitType.EventSubagentCreated || type == AgentCommitType.EventSubagentCompleted)
    val nonce = storage.cacheNonce.value
    suspend fun isCurrent(): Boolean {
        currentCoroutineContext().ensureActive()
        return isBound() && storage.cacheNonce.value == nonce
    }

    return try {
        if (!isCurrent()) return SubagentReturnAnchorResult.Invalidated
        val types = setOf(type)
        var last = storage.latestIndex()
        if (!isCurrent()) return SubagentReturnAnchorResult.Invalidated
        while (last >= 0) {
            val page = storage.getLastCommitsIn(0..last, types, AnchorPageSize)
            if (!isCurrent()) return SubagentReturnAnchorResult.Invalidated
            if (page.isEmpty()) break
            val match = page.filter { row ->
                when (val commit = row.commit) {
                    is StableSubagentCreatedEvent -> type == AgentCommitType.EventSubagentCreated && commit.childAgentId == childAgentId
                    is StableSubagentCompletedEvent -> type == AgentCommitType.EventSubagentCompleted && commit.childAgentId == childAgentId
                    else -> false
                }
            }.maxByOrNull { it.index }
            if (match != null) return SubagentReturnAnchorResult.Found(match.index, nonce)
            if (page.size < AnchorPageSize) break
            val oldest = page.minOf { it.index }
            check(oldest in 0..last) { "The parent history page did not advance." }
            last = oldest - 1
        }
        SubagentReturnAnchorResult.Missing
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: CacheNonceMismatch) {
        SubagentReturnAnchorResult.Invalidated
    } catch (_: AgentNotActive) {
        SubagentReturnAnchorResult.Invalidated
    } catch (_: AgentNotFound) {
        SubagentReturnAnchorResult.Invalidated
    } catch (failure: Throwable) {
        // Native kRPC can carry an untyped remote failure as a bare Throwable.
        if (failure is Error) throw failure
        if (!isCurrent()) SubagentReturnAnchorResult.Invalidated else SubagentReturnAnchorResult.ReadFailed(failure)
    }
}

private const val AnchorPageSize = 64
