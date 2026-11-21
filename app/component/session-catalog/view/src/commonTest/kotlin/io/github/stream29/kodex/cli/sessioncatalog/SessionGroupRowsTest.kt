package io.github.stream29.kodex.cli.sessioncatalog

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionGroupFocus
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionGroupPage
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionGroupSummary
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionGroupsState
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val sessionGroupRowsTest by testSuite {
    test("directory labels preserve exact keys and distinguish duplicate basenames by shortest parents") {
        val labels = groupLabels(listOf(null, "/", "C:\\", "D:/", "/one/common/proj/", "/two/common/proj",
            "C:\\dev\\proj\\", "projects\\misc", "未设置目录"))
        assertEquals(SessionDirectoryLabel("未设置目录", null), labels[null])
        assertEquals(SessionDirectoryLabel("/", null), labels["/"])
        assertEquals(SessionDirectoryLabel("C:\\", null), labels["C:\\"])
        assertEquals(SessionDirectoryLabel("D:/", null), labels["D:/"])
        assertEquals(SessionDirectoryLabel("proj", "one/common"), labels["/one/common/proj/"])
        assertEquals(SessionDirectoryLabel("proj", "two/common"), labels["/two/common/proj"])
        assertEquals(SessionDirectoryLabel("proj", "dev"), labels["C:\\dev\\proj\\"])
        assertEquals(SessionDirectoryLabel("misc", null), labels["projects\\misc"])
        assertEquals(SessionDirectoryLabel("未设置目录", "未设置目录"), labels["未设置目录"])
    }

    test("manual collapse overrides focus; no focused Agent does not expand the null cwd") {
        val state = SessionGroupsState(groups = listOf("/a", "/b", "/c", null).map {
            SessionGroupSummary(it, 1, null, false)
        })
        fun expanded(s: SessionGroupsState, focus: SessionGroupFocus?) = s.rows(focus)
            .filterIsInstance<SessionGroupRow.Header>().filter { it.expanded }.map { it.cwd }
        assertEquals(listOf("/a", "/b"), expanded(state, null))
        assertEquals(listOf("/a", "/b", null), expanded(state, SessionGroupFocus(null, 9)))
        assertEquals(listOf("/b", "/c"), expanded(
            state.copy(manualExpansion = mapOf("/a" to false, "/c" to true, null to false)), SessionGroupFocus(null, 9),
        ))
    }

    test("retained unbounded pages are immediately capped again; archived rows and selected ID are exact") {
        val entries = (10 downTo 2).map { id ->
            SessionCatalogEntry(sessionIndex = id, archived = id == 8, rootAgentId = 100 + id)
        }
        val state = SessionGroupsState(
            groups = listOf(SessionGroupSummary("/a", 1, null, false)),
            pages = mapOf("/a" to SessionGroupPage("/a", 1, entries, 0)),
            expandedLimitGroups = setOf("/a"),
            showArchivedGroups = setOf("/a"),
        )
        val focused = SessionGroupFocus("/a", 108)
        val all = state.rows(focused)
        assertEquals(entries, all.filterIsInstance<SessionGroupRow.Entry>().map { it.entry })
        assertEquals(8, all.filterIsInstance<SessionGroupRow.Entry>().single { it.selected }.entry.sessionIndex)
        assertEquals(SessionGroupRow.Limit("/a", null), all.last())
        val limited = state.copy(expandedLimitGroups = emptySet(), showArchivedGroups = emptySet()).rows(focused)
        val visible = limited.filterIsInstance<SessionGroupRow.Entry>()
        assertEquals(listOf(10, 9, 7, 6, 5), visible.map { it.entry.sessionIndex })
        assertFalse(visible.any { it.selected })
        assertEquals(SessionGroupRow.Limit("/a", 3), limited.last())
    }

    test("all archived leaves the group header and initial empty and loading differ") {
        assertEquals(listOf(SessionGroupRow.Loading), SessionGroupsState().rows(null))
        assertEquals(listOf(SessionGroupRow.Empty), SessionGroupsState(groups = emptyList()).rows(null))
        val rows = SessionGroupsState(
            groups = listOf(SessionGroupSummary("/archive", 7, null, false)),
            pages = mapOf("/archive" to SessionGroupPage("/archive", 7, emptyList(), 0)),
        ).rows(null)
        assertTrue((rows.single() as SessionGroupRow.Header).expanded)
    }
}
