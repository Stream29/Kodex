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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

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
    range: IntRange = 0..Int.MAX_VALUE,
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
        var last = minOf(storage.latestIndex(), range.last)
        if (!isCurrent()) return SubagentReturnAnchorResult.Invalidated
        while (last >= range.first) {
            val page = storage.getLastCommitsIn(range.first..last, types, AnchorPageSize)
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
            check(oldest in range.first..last) { "The parent history page did not advance." }
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

internal fun ObservableKodexAgentStorage.observeSubagentCompletionAnchor(
    childAgentId: Int, isBound: () -> Boolean,
): Flow<Boolean> = flow {
    var observedNonce: Long? = null
    var checkedThrough = -1
    var found = false
    combine(cacheNonce, latestIndexFlow) { nonce, latest -> nonce to latest }.collect { (nonce, latest) ->
        if (nonce != observedNonce || latest < checkedThrough) {
            observedNonce = nonce
            checkedThrough = -1
            found = false
            emit(false)
        }
        if (!found && latest > checkedThrough) {
            when (val result = findSubagentReturnAnchor(
                this@observeSubagentCompletionAnchor, childAgentId, AgentCommitType.EventSubagentCompleted,
                range = (checkedThrough + 1)..latest,
                isBound = { cacheNonce.value == nonce && isBound() },
            )) {
                is SubagentReturnAnchorResult.Found -> {
                    found = result.cacheNonce == cacheNonce.value && isBound()
                    emit(found)
                }
                SubagentReturnAnchorResult.Missing -> checkedThrough = latest
                else -> Unit
            }
        }
    }
}.distinctUntilChanged()

private const val AnchorPageSize = 64
