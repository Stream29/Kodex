package io.github.stream29.kodex.cli.app

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
import com.jakewharton.mosaic.ui.Text
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.cli.components.rememberTuiPopupAnchor
import io.github.stream29.kodex.cli.components.tuiPopupAnchor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.datetime.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

val contextMenuTimestampTest by testSuite {
    test("local seconds retain the instant's actual UTC offset") {
        val timestamp = Instant.parse("2026-09-08T01:02:03.456Z")
        assertEquals("2026-09-08 09:02:03 UTC+08:00", formatMenuTimestamp(timestamp, TimeZone.of("Asia/Singapore")))
        assertEquals("2026-09-08 01:02:03 UTC+00:00", formatMenuTimestamp(timestamp, TimeZone.UTC))
        assertEquals("2026-09-07 21:02:03 UTC-04:00", formatMenuTimestamp(timestamp, TimeZone.of("America/New_York")))
        assertEquals(
            "2026-01-07 20:02:03 UTC-05:00",
            formatMenuTimestamp(Instant.parse("2026-01-08T01:02:03Z"), TimeZone.of("America/New_York")),
        )
    }

    test("loading and failure hide independently while reopening reads a new snapshot") {
        val first = Instant.parse("2026-09-08T01:02:03Z")
        val second = Instant.parse("2026-09-09T01:02:03Z")
        val delayed = CompletableDeferred<Instant>()
        var opening by mutableStateOf(0)
        var available = first
        var reads = 0
        runMosaicTest {
            setContentAndSnapshot {
                val created = rememberMenuTimestamp(opening) {
                    reads++
                    if (opening == 0) delayed.await() else available
                }
                val updated = rememberMenuTimestamp(opening) { error("unreadable") }
                Text("opening=$opening created=${created.orEmpty()} updated=${updated.orEmpty()}")
            }
            assertTrue("created= updated=" in timestampSnapshot("opening=0"))
            delayed.complete(first)
            timestampSnapshot("2026-09-08")
            assertEquals(1, reads)
            available = second
            assertFalse("2026-09-09" in draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false))
            opening++
            val next = timestampSnapshot("2026-09-09")
            assertEquals(2, reads)
            assertTrue(next.trimEnd().endsWith("updated="), next)
        }
    }

    test("replacing a request discards a pending timestamp") {
        var opening by mutableStateOf(0)
        val pending = CompletableDeferred<Instant>()
        runMosaicTest {
            setContentAndSnapshot {
                val timestamp = rememberMenuTimestamp(opening) {
                    if (opening == 0) pending.await() else null
                }
                Text("opening=$opening timestamp=${timestamp.orEmpty()}")
            }
            timestampSnapshot("opening=0")
            opening++
            timestampSnapshot("opening=1")
            pending.complete(Instant.parse("2026-09-08T00:00:00Z"))
            val snapshot = draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
            assertFalse("2026-09-08" in snapshot, snapshot)
        }
    }

    test("Message information precedes actions and keyboard skips readonly rows") {
        var selected by mutableStateOf("none")
        runMosaicTest {
            setContentAndSnapshot {
                val anchor = rememberTuiPopupAnchor()
                TuiPopupHost(Modifier.width(64).height(10)) {
                    Text("message", Modifier.tuiPopupAnchor(anchor))
                    HistoryEntryContextMenuPopup(
                        anchor, null, {}, { selected = "revert" }, { selected = "fork" },
                        messageIndex = 7, timestamp = "2026-09-08 09:02:03 UTC+08:00",
                    )
                    Text("selection=$selected")
                }
            }
            val snapshot = timestampSnapshot("Timestamp:")
            assertTrue(snapshot.indexOf("Index: 7") < snapshot.indexOf("Timestamp:"), snapshot)
            assertTrue(snapshot.indexOf("Timestamp:") < snapshot.indexOf("Revert to here"), snapshot)
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            timestampSnapshot("selection=revert")
        }
    }

    test("late timestamp insertion preserves the focused action") {
        val delayed = CompletableDeferred<Instant>()
        val request = Any()
        var selected by mutableStateOf("none")
        runMosaicTest {
            setContentAndSnapshot {
                val anchor = rememberTuiPopupAnchor()
                val timestamp = rememberMenuTimestamp(request) { delayed.await() }
                TuiPopupHost(Modifier.width(64).height(10)) {
                    Text("message", Modifier.tuiPopupAnchor(anchor))
                    HistoryEntryContextMenuPopup(
                        anchor, null, {}, { selected = "revert" }, { selected = "fork" },
                        messageIndex = 7, timestamp = timestamp,
                    )
                    Text("selection=$selected")
                }
            }
            val initial = timestampSnapshot("Fork from here")
            assertFalse("Timestamp:" in initial, initial)
            sendKeyEvent(KeyboardEvent(KeyboardEvent.Down))
            awaitSnapshot()
            delayed.complete(Instant.parse("2026-09-08T01:02:03Z"))
            timestampSnapshot("Timestamp:")
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            timestampSnapshot("selection=fork")
        }
    }

    test("narrow Message menus retain their actions without a timestamp placeholder") {
        runMosaicTest {
            setContentAndSnapshot {
                val anchor = rememberTuiPopupAnchor()
                TuiPopupHost(Modifier.width(22).height(10)) {
                    Text("message", Modifier.tuiPopupAnchor(anchor))
                    HistoryEntryContextMenuPopup(
                        anchor, null, {}, {}, {}, messageIndex = 7,
                        timestamp = "2026-09-08 09:02:03 UTC+08:00",
                    )
                }
            }
            val snapshot = timestampSnapshot("Fork from here")
            assertTrue("Index: 7" in snapshot, snapshot)
            assertTrue("Timestamp:" in snapshot, snapshot)
            assertFalse("Unavailable" in snapshot || "Loading" in snapshot || "Error" in snapshot, snapshot)
        }
    }
}

private suspend fun TestMosaic<String>.timestampSnapshot(expected: String): String {
    var snapshot = draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
    repeat(10) {
        if (expected in snapshot) return snapshot
        snapshot = try {
            awaitSnapshot()
        } catch (_: TimeoutCancellationException) {
            draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
        }
    }
    assertTrue(expected in snapshot, snapshot)
    return snapshot
}
