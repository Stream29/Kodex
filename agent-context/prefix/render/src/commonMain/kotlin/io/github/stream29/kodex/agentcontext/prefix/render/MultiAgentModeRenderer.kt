package io.github.stream29.kodex.agentcontext.prefix.render

import io.github.stream29.kodex.openai.AgentMode

/** Renders the developer policy for this Agent's delegation mode. */
public fun AgentMode.renderMultiAgentMode(): String =
    "$MultiAgentModeOpeningTag${multiAgentModeBody()}$MultiAgentModeClosingTag"

private fun AgentMode.multiAgentModeBody(): String =
    when (this) {
        AgentMode.Single -> SingleAgentInstructions
        AgentMode.Multi -> MultiAgentInstructions
    }

private const val MultiAgentModeOpeningTag: String = "<multi_agent_mode>"
private const val MultiAgentModeClosingTag: String = "</multi_agent_mode>"

private const val SingleAgentInstructions: String =
    "Single-agent execution is active. Do not spawn or interact with sub-agents. " +
        "Multi-agent tools are unavailable. This mode remains active until a later " +
        "multi-agent mode developer message changes it."

private const val MultiAgentInstructions: String =
    "Proactive multi-agent delegation is active. Any earlier instruction requiring an explicit " +
        "user request before spawning sub-agents no longer applies. Use sub-agents when parallel " +
        "work would materially improve speed or quality. This mode remains active until a later " +
        "multi-agent mode developer message changes it."
