package io.github.stream29.kodex.app.webrenderersmoke

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.ui.compose.rememberComposeAgentStatusRenderer
import io.github.stream29.kodex.cli.app.ApplicationDraftStatusContent
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import kotlinx.io.files.Path
import kotlin.test.*

val offscreenDraftStatusTest by testSuite {
    test("draft status borrows the runtime child and preserves exact directory and settings intents") { runtimeScene { _, child, _ ->
        val settings = KodexAgentSettings(OpenAiModelId("not-the-runtime-child"), cwd = Path("/exact/root-draft"))
        var directories = 0
        var openedSettings = 0
        val scene = OffscreenScene.create {
            ApplicationDraftStatusContent(
                settings, child, rememberComposeAgentStatusRenderer(),
                { directories++ }, { openedSettings++ },
            )
        }
        try {
            scene.settle { scene.hasText("test-model max fast") && scene.hasDescription("Working directory: /exact/root-draft") }
            assertFalse(scene.hasText("not-the-runtime-child"))
            scene.clickDescription("Working directory: /exact/root-draft")
            scene.click("Settings")
            scene.settle { directories == 1 && openedSettings == 1 }
            assertFalse(child.state.value.closed)
            scene.frame()
        } finally { scene.close() }
        assertFalse(child.state.value.closed)
    } }
}
