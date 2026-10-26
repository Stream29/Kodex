package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemState
import io.github.stream29.kodex.openai.ContentItem
import kotlin.test.*
import kotlin.time.Duration

val applicationMenuContentTest by testSuite {
    test("tab information is optional and actions retain ordering and exact callbacks") {
        val calls = mutableListOf<String>()
        val entries = applicationTabMenuEntries(
            42, "created", null, { calls += "rename" }, { calls += "close" },
            { calls += "archive" }, { calls += "delete" },
        )
        assertEquals(listOf("Index: 42", "Created at: created"), assertIs<ApplicationMenuEntry.Information>(entries.first()).lines)
        val actions = entries.filterIsInstance<ApplicationMenuEntry.Action>()
        assertEquals(listOf("rename", "close", "close-and-archive", "delete"), actions.map { it.key })
        assertEquals(listOf("Rename", "Close", "Close and archive", "Delete"), actions.map { it.label })
        actions.forEach { it.onClick() }
        assertEquals(listOf("rename", "close", "archive", "delete"), calls)
    }
    test("non-message history has no optional information and editing is an explicit capability") {
        val calls = mutableListOf<String>()
        val plain = applicationHistoryMenuEntries(null, "not a message timestamp", {}, {}, null)
        assertTrue(plain.all { it is ApplicationMenuEntry.Action })
        assertEquals(listOf("revert-to-here", "fork-from-here"), plain.map { it.key })
        val editable = applicationHistoryMenuEntries(7, "time", { calls += "revert" }, { calls += "fork" }, { calls += "edit" })
        assertEquals(listOf("Index: 7", "Timestamp: time"), assertIs<ApplicationMenuEntry.Information>(editable.first()).lines)
        editable.filterIsInstance<ApplicationMenuEntry.Action>().forEach { it.onClick() }
        assertEquals(listOf("revert", "fork", "edit"), calls)
    }
    test("only ready entirely textual user messages round trip without trimming") {
        fun ready(content: List<ContentItem>) = MessageHistoryItemState.Ready(StableUserMessage(content), Duration.ZERO)
        assertEquals("  第一行\n\n第二行  ", ready(listOf(ContentItem.InputText("  第一行\n"), ContentItem.OutputText("\n第二行  "))).revertAndEditText())
        assertEquals("", ready(emptyList()).revertAndEditText())
        assertNull(ready(listOf(ContentItem.InputText("text"), ContentItem.InputImage("https://example.invalid/image"))).revertAndEditText())
        assertNull(MessageHistoryItemState.Ready(StableAssistantMessage(listOf(ContentItem.OutputText("text"))), Duration.ZERO).revertAndEditText())
        assertNull(MessageHistoryItemState.Failed.revertAndEditText())
        assertNull(null.revertAndEditText())
    }
}
