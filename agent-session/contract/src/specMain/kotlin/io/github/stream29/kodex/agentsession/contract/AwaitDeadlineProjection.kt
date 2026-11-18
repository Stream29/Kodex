package io.github.stream29.kodex.agentsession.contract

import io.github.stream29.kodex.agentruntime.contract.PendingAwaitDeadline
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.UnstableSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingAwait

/** Projects the earliest armed timeout; a Fork does not inherit its source Agent's arming. */
public fun UnstableSnapshot.pendingDeadline(agentId: Int, snapshotIndex: Int): PendingAwaitDeadline? =
    events.asSequence()
        .filterIsInstance<PendingAwait>()
        .filter { it.armedForAgentId == agentId }
        .mapNotNull { pending ->
            pending.timeoutAt?.let { PendingAwaitDeadline(snapshotIndex, pending.callId, it) }
        }
        .minWithOrNull(compareBy<PendingAwaitDeadline> { it.timeoutAt }.thenBy { it.callId })
