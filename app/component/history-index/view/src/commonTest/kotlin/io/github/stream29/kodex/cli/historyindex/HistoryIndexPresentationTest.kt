package io.github.stream29.kodex.cli.historyindex

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.*
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.tool.requestuserinput.*
import kotlin.test.*

val historyIndexPresentationTest by testSuite {
    test("row branches and sparse graph remain exact without platform measurement") {
        assertEquals(HistoryIndexRowPresentation("┌● …", false),
            historyIndexRowPresentation(HistoryIndexReadState.Loading, "┌●"))
        assertEquals(HistoryIndexRowPresentation("┌● [error]", true),
            historyIndexRowPresentation(HistoryIndexReadState.Failed, "┌●"))
        assertNull(historyIndexRowPresentation(HistoryIndexReadState.Closed, "┌●"))
        assertEquals(HistoryIndexRowPresentation("● Original", false), historyIndexRowPresentation(
            HistoryIndexReadState.Ready(HistoryIndexEntry(7, HistoryIndexEntryKind.UserMessage, "Original")), "●",
        ))
        assertEquals(listOf("┌●", "├●", "└●"), (0..2).map { historyIndexGraph(it, 3) })
        assertEquals("●", historyIndexGraph(0, 1))
    }
    test("detail Loading and Closed are hidden and Failed is a safe message") {
        assertNull(historyIndexDetailPresentation(HistoryIndexReadState.Loading))
        assertNull(historyIndexDetailPresentation(HistoryIndexReadState.Closed))
        assertEquals(HistoryIndexDetailPresentation("Error", "Unable to read or decode the history entry.", null, true),
            historyIndexDetailPresentation(HistoryIndexReadState.Failed))
        assertEquals(HistoryIndexDetailPresentation("Assistant final", "Full content", null, false), historyIndexDetailPresentation(
            HistoryIndexReadState.Ready(HistoryIndexEntryDetail(HistoryIndexEntryKind.AssistantFinal, "Full content")),
        ))
    }
    test("read-only question projection redacts before any popup renderer and drops the raw fallback") {
        val event = StableRequestUserInputToolEvent(
            "secret", arguments = RequestUserInputArgs(listOf(
                RequestUserInputQuestion("secret", "Password", "Enter password", isSecret = true),
            )),
            result = StableRequestUserInputResult.Answered(RequestUserInputResponse(mapOf(
                "secret" to RequestUserInputAnswer(listOf("SECRET")),
            ))),
        )
        val presentation = assertNotNull(historyIndexDetailPresentation(HistoryIndexReadState.Ready(
            HistoryIndexEntryDetail(HistoryIndexEntryKind.RequestUserInput, "SECRET raw fallback", event),
        )))
        assertEquals("", presentation.content)
        assertEquals(listOf("Password: Enter password", "  > [hidden]"), assertNotNull(presentation.questionRows).map { it.value })
        assertFalse(presentation.questionRows.orEmpty().any { "SECRET" in it.value })
    }
}
