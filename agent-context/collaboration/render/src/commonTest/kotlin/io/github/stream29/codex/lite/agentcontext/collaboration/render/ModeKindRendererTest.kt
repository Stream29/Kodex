package io.github.stream29.codex.lite.agentcontext.collaboration.render

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.openai.ModeKind
import kotlin.test.assertTrue

val modeKindRendererTest by testSuite {
    test("renders update plan guidance for Default mode") {
        val rendered = ModeKind.Default.render()

        assertTrue(rendered.startsWith("<collaboration_mode>## Planning\n"))
        assertTrue(rendered.contains("You have access to an `update_plan` tool"))
        assertTrue(rendered.contains("There should always be exactly one `in_progress` step"))
        assertTrue(rendered.endsWith("\n</collaboration_mode>"))
    }

    test("renders the built-in Plan mode developer block") {
        val rendered = ModeKind.Plan.render()

        assertTrue(rendered.startsWith("<collaboration_mode># Plan Mode (Conversational)\n"))
        assertTrue(rendered.contains("Plan Mode is not changed by user intent"))
        assertTrue(rendered.contains("`update_plan` is a checklist/progress/TODOs tool"))
        assertTrue(rendered.endsWith("\n</collaboration_mode>"))
    }
}
