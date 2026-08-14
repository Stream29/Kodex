package io.github.stream29.kodex.agentcontext.prefix.render

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.AgentMode
import kotlin.test.assertTrue

val multiAgentModeRendererTest by testSuite {
    test("renders hard single agent guidance") {
        val rendered = AgentMode.Single.renderMultiAgentMode()

        assertTrue(rendered.startsWith("<multi_agent_mode>"))
        assertTrue(rendered.contains("Do not spawn or interact with sub-agents."))
        assertTrue(rendered.contains("Multi-agent tools are unavailable."))
        assertTrue(rendered.endsWith("</multi_agent_mode>"))
    }

    test("renders proactive multi agent guidance") {
        val rendered = AgentMode.Multi.renderMultiAgentMode()

        assertTrue(rendered.contains("Proactive multi-agent delegation is active."))
        assertTrue(rendered.contains("Use sub-agents when parallel work would materially improve"))
    }
}
