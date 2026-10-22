package io.github.stream29.kodex.cli.agent

import io.github.stream29.kodex.rpc.models.AgentStateValue

/** Small terminal-ready status label for one execution phase. */
public expect fun AgentStateValue.label(): String
