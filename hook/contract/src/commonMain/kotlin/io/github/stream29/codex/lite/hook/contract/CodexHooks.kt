package io.github.stream29.codex.lite.hook.contract

import io.github.stream29.codex.lite.hook.contract.approval.ApprovalHooks
import io.github.stream29.codex.lite.hook.contract.approval.NoOpApprovalHooks
import io.github.stream29.codex.lite.hook.contract.compaction.CompactionHooks
import io.github.stream29.codex.lite.hook.contract.compaction.NoOpCompactionHooks
import io.github.stream29.codex.lite.hook.contract.session.NoOpSessionLifecycleHooks
import io.github.stream29.codex.lite.hook.contract.session.SessionLifecycleHooks
import io.github.stream29.codex.lite.hook.contract.tool.NoOpToolHooks
import io.github.stream29.codex.lite.hook.contract.tool.ToolHooks
import io.github.stream29.codex.lite.hook.contract.turn.NoOpTurnHooks
import io.github.stream29.codex.lite.hook.contract.turn.TurnHooks
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Complete hook capability and the lifecycle of its runtime tasks. */
public interface CodexHooks :
    CoroutineScope,
    TurnHooks,
    ToolHooks,
    CompactionHooks,
    SessionLifecycleHooks,
    ApprovalHooks

/** Complete hook capability with no configured behavior. */
public data object NoOpCodexHooks :
    CodexHooks,
    TurnHooks by NoOpTurnHooks,
    ToolHooks by NoOpToolHooks,
    CompactionHooks by NoOpCompactionHooks,
    SessionLifecycleHooks by NoOpSessionLifecycleHooks,
    ApprovalHooks by NoOpApprovalHooks {
    override val coroutineContext: CoroutineContext = EmptyCoroutineContext
}
