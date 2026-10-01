package io.github.stream29.kodex.agentcontext.prefix.render

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertTrue

val planningInstructionsRendererTest by testSuite {
    test("renders the built-in update plan guidance") {
        val rendered = renderPlanningInstructions()

        assertTrue(rendered.startsWith("## Planning\n"))
        assertTrue(rendered.contains("You have access to an `update_plan` tool"))
        assertTrue(rendered.contains("There should always be exactly one `in_progress` step"))
        assertTrue(rendered.endsWith("\n"))
    }
}
