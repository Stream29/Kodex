package io.github.stream29.codex.lite.agentcontext.collaboration.render

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.openai.ModeKind
import kotlin.test.assertNull
import kotlin.test.assertTrue

val modeKindRendererTest by testSuite {
    test("does not render a collaboration block for Default mode") {
        assertNull(ModeKind.Default.render())
    }

    test("renders the built-in Plan mode developer block") {
        val rendered = checkNotNull(ModeKind.Plan.render())

        assertTrue(rendered.startsWith("<collaboration_mode># Plan Mode (Conversational)\n"))
        assertTrue(rendered.contains("Plan Mode is not changed by user intent"))
        assertTrue(rendered.contains("`update_plan` is a checklist/progress/TODOs tool"))
        assertTrue(rendered.endsWith("\n</collaboration_mode>"))
    }
}
