package io.github.stream29.kodex.hook.contract

import io.github.stream29.kodex.hook.contract.approval.ApprovalHooks
import io.github.stream29.kodex.hook.contract.approval.NoOpApprovalHooks
import io.github.stream29.kodex.hook.contract.compaction.CompactionHooks
import io.github.stream29.kodex.hook.contract.compaction.NoOpCompactionHooks
import io.github.stream29.kodex.hook.contract.tool.NoOpToolHooks
import io.github.stream29.kodex.hook.contract.tool.ToolHooks
import io.github.stream29.kodex.hook.contract.turn.NoOpTurnHooks
import io.github.stream29.kodex.hook.contract.turn.TurnHooks
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Complete hook capability and the lifecycle of its runtime tasks. */
public interface KodexHooks :
    CoroutineScope,
    TurnHooks,
    ToolHooks,
    CompactionHooks,
    ApprovalHooks

/** Complete hook capability with no configured behavior. */
public data object NoOpKodexHooks :
    KodexHooks,
    TurnHooks by NoOpTurnHooks,
    ToolHooks by NoOpToolHooks,
    CompactionHooks by NoOpCompactionHooks,
    ApprovalHooks by NoOpApprovalHooks {
    override val coroutineContext: CoroutineContext = EmptyCoroutineContext
}
