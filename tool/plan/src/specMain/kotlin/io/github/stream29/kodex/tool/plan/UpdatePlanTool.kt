package io.github.stream29.kodex.tool.plan

import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.agentstate.contract.appendPlanUpdate
import io.github.stream29.kodex.tool.contract.Tool

/**
 * Creates the ordinary `update_plan` tool bound to this Agent.
 *
 * A successful call updates its parsed plan and completes its tool output
 * through [appendPlanUpdate]. The generic tool runtime therefore must
 * not append the returned output again when the call is no longer pending.
 */
public expect fun KodexAgentState.updatePlanTool(): Tool
