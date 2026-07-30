package io.github.stream29.kodex.agentcontext.skill.render

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentcontext.prefix.skill.contract.AvailableSkill
import io.github.stream29.kodex.agentcontext.prefix.skill.contract.SkillScope
import io.github.stream29.kodex.agentcontext.prefix.skill.contract.SkillSource
import io.github.stream29.kodex.agentcontext.skill.contract.SkillDocument
import kotlinx.io.files.Path
import kotlin.test.assertEquals

val skillDocumentRendererTest by testSuite {
    test("renders a selected skill as durable contextual input") {
        val skill = AvailableSkill(
            name = "gradle",
            description = "Build and test Gradle projects.",
            path = Path("/skills/gradle/SKILL.md"),
            source = SkillSource(
                authorityId = "test",
                scope = SkillScope.User,
                root = Path("/skills"),
            ),
        )

        assertEquals(
            """
            <skill>
            <name>gradle</name>
            <path>/skills/gradle/SKILL.md</path>
            ---
            description: Build projects.
            ---
            Follow the instructions.
            </skill>
            """.trimIndent(),
            SkillDocument(
                skill = skill,
                instructions = """
                    ---
                    description: Build projects.
                    ---
                    Follow the instructions.
                """.trimIndent(),
            ).render(),
        )
    }
}
