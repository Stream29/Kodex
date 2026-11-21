package io.github.stream29.kodex.agentcontext.prefix

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertContains

val subagentHierarchyInstructionsTest by testSuite {
    test("includes hierarchy path and explicit kodex agent complete guidance") {
        val rendered = subagentHierarchyInstructions(listOf(2, 41, 708))

        assertContains(rendered, "You are subagent #708, delegated by Agent #41.")
        assertContains(rendered, "2 → 41 → 708")
        assertContains(rendered, "`kodex agent complete`")
    }
}
