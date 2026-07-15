package io.github.stream29.codex.lite.agentcontext.skill.render

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentcontext.skill.contract.AvailableSkill
import kotlinx.io.files.Path
import kotlin.test.assertEquals

val availableSkillRendererTest by testSuite {
    test("renders the available skills catalog") {
        assertEquals(
            """
            <skills_instructions>
            ## Skills
            A skill is a set of local instructions stored in a `SKILL.md` file. Below is the list of skills available to the agent. Each entry includes a name, description, and path.
            ### Available skills
            - name: gradle
              description: Build and test Gradle projects.
              path: /skills/gradle/SKILL.md
            </skills_instructions>
            """.trimIndent(),
            listOf(
                AvailableSkill(
                    name = "gradle",
                    description = "Build and test Gradle projects.",
                    path = Path("/skills/gradle/SKILL.md"),
                ),
            ).render(),
        )
    }
}
