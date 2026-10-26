package io.github.stream29.kodex.cli.agent

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.app.agent.contract.ComposerOwnerId
import io.github.stream29.kodex.app.agent.contract.ComposerState
import io.github.stream29.kodex.openai.ContentItem
import kotlin.test.assertEquals
import kotlin.test.assertNull

val composerPresentationTest by testSuite {
    test("preview preserves Unicode and hard-line content before renderer truncation") {
        val pending = listOf(StableUserMessage(listOf(ContentItem.InputText("  你好\n世界  "))))
        assertEquals(listOf("Pending steer (1)", "  ↳ 你好 世界"), pendingSteerPreviewContent(pending, 4))
        assertEquals(emptyList(), pendingSteerPreviewContent(pending, 0))
        assertEquals(listOf("Pending steer (1)"), pendingSteerPreviewContent(pending, 1))
    }
    test("preview reports omitted entries only when there is room") {
        val pending = (0..4).map { StableUserMessage(listOf(ContentItem.InputText("message $it"))) }
        assertEquals(
            listOf("Pending steer (5)", "  ↳ message 0", "  … 4 more"),
            pendingSteerPreviewContent(pending, 3),
        )
        assertEquals(listOf("Pending steer (5)", "  ↳ message 0"), pendingSteerPreviewContent(pending, 2))
    }
    test("status never manufactures a hint for a blank or idle draft") {
        val state = ComposerState(ComposerOwnerId("fixture"), running = true)
        assertNull(composerStatusLine(state))
        assertEquals("Submit to steer", composerStatusLine(state.copy(text = "继续", cursorOffset = 2)))
        assertNull(composerStatusLine(state.copy(running = false)))
    }
}
