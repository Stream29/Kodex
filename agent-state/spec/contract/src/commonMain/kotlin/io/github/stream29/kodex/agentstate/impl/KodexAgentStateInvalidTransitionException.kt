package io.github.stream29.kodex.agentstate.impl

import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue

/**
 * An atomic operation cannot start or commit from [currentState].
 *
 * The historical package is retained; the observable exception belongs to the
 * AgentState specification, not to a particular implementation.
 */
public class KodexAgentStateInvalidTransitionException(
    operation: String,
    public val currentState: KodexAgentStateValue,
) : IllegalStateException("Cannot $operation while agent state is $currentState.")
