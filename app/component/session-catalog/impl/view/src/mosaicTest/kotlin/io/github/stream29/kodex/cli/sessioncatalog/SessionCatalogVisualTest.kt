package io.github.stream29.kodex.cli.sessioncatalog

import io.github.stream29.kodex.cli.components.formatPopupTimestamp as formatMenuTimestamp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.utils.terminaltext.terminalCellWidth
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.datetime.TimeZone
import kotlin.test.*
import kotlin.time.Instant

val sessionCatalogVisualTest by testSuite {
    test("labels retain fallback, Unicode cell width, dim suffix, and independent running") {
        val now = Instant.parse("2026-09-28T10:30:00Z")
        assertEquals("Session 7", SessionCatalogEntry(7).sessionBrowserLabel(80, now).text)
        assertEquals("idle", SessionCatalogEntry(7, "idle", isActive = true).sessionBrowserLabel(80, now).text)
        val running = SessionCatalogEntry(
            7, "中🙂abcdefghijklmnop",
            updatedAt = Instant.parse("2026-09-28T10:25:00Z"), running = true,
        )
        val label = running.sessionBrowserLabel(18, now, "⠹")
        assertTrue(label.text.startsWith("⠹"), label.text)
        assertTrue(label.text.endsWith(" 5m ago"), label.text)
        assertEquals(18, label.text.terminalCellWidth())
        assertTrue(label.spanStyles.isNotEmpty())
        assertTrue(running.sessionBrowserLabel(3, now).text.terminalCellWidth() <= 3)
        assertEquals("title now", SessionCatalogEntry(0, "title", updatedAt = now).sessionBrowserLabel(80, now).text)
    }

    test("menu dates retain second resolution, system-offset syntax and null omission") {
        val timestamp = Instant.parse("2026-01-02T03:04:05.123456789Z")
        assertEquals(
            "2026-01-02 03:04:05 UTC+00:00",
            formatMenuTimestamp(timestamp, TimeZone.UTC),
        )
        assertEquals(
            "2026-01-02 11:04:05 UTC+08:00",
            formatMenuTimestamp(timestamp, TimeZone.of("Asia/Singapore")),
        )
    }

    for (key in listOf(
        KeyboardEvent(KeyboardEvent.Menu),
        KeyboardEvent(KeyboardEvent.F10, modifiers = KeyboardEvent.ModifierShift),
    )) {
        test("keyboard menu ${key.codepoint} renders captured dates and routes unarchive") {
            var anchor by mutableStateOf<TuiPopupAnchor?>(null)
            var result by mutableStateOf("none")
            val entry = SessionCatalogEntry(9, "archived", archived = true)
            runMosaicTest {
                setContentAndSnapshot {
                    TuiPopupHost(modifier = Modifier.width(80).height(12)) {
                        Column {
                            SessionCatalogRow(
                                entry, 60,
                                onClick = { result = "open" },
                                onOpenContextMenu = { next, position ->
                                    assertNull(position)
                                    anchor = next
                                },
                            )
                            Text("result=$result")
                        }
                        anchor?.let {
                            SessionCatalogContextMenuPopup(
                                entry, it, null,
                                onDismissRequest = { anchor = null },
                                onFork = { result = "fork"; anchor = null },
                                onArchive = { result = "archive"; anchor = null },
                                onUnarchive = { result = "unarchive"; anchor = null },
                                onDelete = { result = "delete"; anchor = null },
                                // Missing timestamp zero is omitted independently of updatedAt.
                                updatedAt = "2026-01-02 03:04:05 UTC+00:00",
                            )
                        }
                    }
                }
                sendKeyEvent(key)
                val menu = visualSnapshot("[Unarchive]")
                assertTrue("Index: 9" in menu, menu)
                assertTrue("Updated at: 2026-01-02 03:04:05 UTC+00:00" in menu, menu)
                assertFalse("Created at:" in menu, menu)
                assertTrue("[Delete]" in menu, menu)
                assertTrue("[Fork]" in menu, menu)
                sendKeyEvent(KeyboardEvent(13))
                visualSnapshot("result=unarchive")
            }
        }
    }

    test("header trailing checkbox and loading animation helpers retain visual behavior") {
        var archived by mutableStateOf(false)
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                SessionCatalogHeader(archived, { archived = it }, Modifier.width(40))
            }
            assertTrue(snapshot.startsWith("Sessions"), snapshot)
            assertTrue(snapshot.endsWith("[ ] Show archived"), snapshot)
            sendKeyEvent(KeyboardEvent(13))
            visualSnapshot("[x] Show archived")
            assertTrue(archived)
        }
        runMosaicTest {
            val first = setContentAndSnapshot { SessionCatalogLoadingIndicator() }
            assertTrue("Loading sessions…" in first, first)
            var changed = first
            repeat(20) { if (changed == first) changed = awaitSnapshot() }
            assertNotEquals(first, changed)
            assertTrue("Loading sessions…" in changed, changed)
        }
    }
}

private suspend fun TestMosaic<String>.visualSnapshot(expected: String): String {
    var latest = ""
    repeat(5) {
        latest = try {
            awaitSnapshot()
        } catch (_: TimeoutCancellationException) {
            draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
        }
        latest = latest.replace(Regex(" +]"), "]")
        if (expected in latest) return latest
    }
    assertTrue(expected in latest, latest)
    return latest
}
