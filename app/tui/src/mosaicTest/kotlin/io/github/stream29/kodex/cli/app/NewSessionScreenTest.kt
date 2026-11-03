package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.focus.focusRequester
import com.jakewharton.mosaic.focus.onFocusChanged
import com.jakewharton.mosaic.layout.onPlaced
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.PasteEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.testing.SnapshotStrategy
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.ui.unit.IntOffset
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.app.agent.contract.ComposerViewModel
import io.github.stream29.kodex.cli.components.TuiButton
import io.github.stream29.kodex.cli.settings.NewLineKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

val newSessionScreenTest by testSuite {
    test("new session title is bold and the editor has a full border") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            runMosaicTest(snapshotStrategy = SnapshotStrategy { mosaic ->
                mosaic.draw().render(AnsiLevel.TRUECOLOR, supportsKittyUnderlines = false)
            }) {
                val snapshot = setContentAndSnapshot {
                    io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                        NewSessionScreen(fixture.newSession(), 40, 10, NewLineKey.ShiftEnter, { 1 }, {}, { Text("STATUS") })
                    }
                }
                assertTrue(Regex("\\u001B\\[[0-9;]*1(?:;[0-9]+)*mStart a new session").containsMatchIn(snapshot), snapshot)
                val plain = snapshot.replace(Regex("\\u001B\\[[0-9;]*m"), "")
                assertContains(plain, "┌" + "─".repeat(38) + "┐")
                assertContains(plain, "└" + "─".repeat(38) + "┘")
                assertTrue(plain.lines().any { it.startsWith("│> ") && it.endsWith("│") }, plain)
                assertContains(plain, "STATUS")
            }
        } finally { fixture.close() }
    }

    test("resize to a short viewport drops the border without losing the draft") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val composer = fixture.newSession().composer
            composer.update("alpha\nbeta", 10)
            var rows by mutableStateOf(8)
            runNewSessionMosaicTest {
                setContentAndSnapshot {
                    io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                        NewSessionScreen(fixture.newSession(), 20, rows, NewLineKey.ShiftEnter, { 1 }, {}, { Text("STATUS") })
                    }
                }
                assertContains(awaitNewSessionSnapshot("border visible") { "┌" in it && "└" in it }, "┌")
                rows = 2
                val short = awaitNewSessionSnapshot("short viewport keeps editor") { "beta" in it && "┌" !in it }
                assertFalse("└" in short, short)
                assertEquals("alpha\nbeta", composer.state.value.text)
                assertEquals(10, composer.state.value.cursorOffset)
            }
        } finally { fixture.close() }
    }

    test("short viewport scrolls focused status slots and returns to the long editor") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val composer = fixture.newSession().composer
            val text = "first\nsecond\nthird\nfourth"
            composer.update(text, text.length)
            var firstClicks = 0
            var lastClicks = 0
            var firstFocused = false
            var lastFocused = false
            var firstPosition: IntOffset? = null
            var lastPosition: IntOffset? = null
            val focusChanges = mutableListOf<String>()
            runNewSessionMosaicTest {
                val initial = setContentAndSnapshot {
                    io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                        NewSessionScreen(
                            fixture.newSession(), 20, 2, NewLineKey.ShiftEnter,
                            { 3 }, {},
                            {
                                Column {
                                    TuiButton("First", modifier = Modifier
                                        .onPlaced { firstPosition = it.position }
                                        .onFocusChanged {
                                            firstFocused = it.isFocused
                                            focusChanges += "First=$firstFocused"
                                        }) { firstClicks++ }
                                    Text("middle")
                                    TuiButton("Last", modifier = Modifier
                                        .onPlaced { lastPosition = it.position }
                                        .onFocusChanged {
                                            lastFocused = it.isFocused
                                            focusChanges += "Last=$lastFocused"
                                        }) { lastClicks++ }
                                }
                            },
                        )
                    }
                }
                assertContains(initial, "fourth")
                assertEquals(IntOffset(0, 1), firstPosition)
                assertEquals(IntOffset(0, 3), lastPosition)
                sendKeyEvent(KeyboardEvent(9))
                assertContains(awaitNewSessionSnapshot("First focused after one Tab") {
                    firstFocused && !lastFocused && "[First]" in it
                }, "[First]")
                sendKeyEvent(KeyboardEvent(13))
                awaitNewSessionSnapshot("First activated exactly once") { firstClicks == 1 }
                assertEquals(1, firstClicks)
                sendKeyEvent(KeyboardEvent(9))
                val last = awaitNewSessionSnapshot(
                    "Last focused after the next single Tab",
                    diagnostics = {
                        "First=$firstFocused at $firstPosition; Last=$lastFocused at $lastPosition; $focusChanges"
                    },
                ) { !firstFocused && lastFocused && "[Last]" in it }
                assertContains(last, "[Last]")
                assertFalse(firstFocused)
                assertTrue(lastFocused)
                assertEquals(IntOffset(0, 1), lastPosition)
                sendKeyEvent(KeyboardEvent(13))
                awaitNewSessionSnapshot("Last activated exactly once") { lastClicks == 1 }
                assertEquals(1, lastClicks)
                sendKeyEvent(KeyboardEvent(9))
                assertContains(awaitNewSessionSnapshot("Tab returns to the editor") {
                    !firstFocused && !lastFocused && "fourth" in it
                }, "fourth")
                sendKeyEvent(KeyboardEvent('!'.code))
                awaitNewSessionSnapshot("Returned editor accepts input") {
                    composer.state.value.text == "$text!" && "fourth!" in it
                }
                assertEquals("$text!", composer.state.value.text)
                assertEquals(1, firstClicks)
                assertEquals(1, lastClicks)
            }
        } finally {
            fixture.close()
        }
    }

    test("short viewport can bring a directly requested status target through nested clipping") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val text = "first\nsecond\nthird\nfourth"
            fixture.newSession().composer.update(text, text.length)
            val lastRequester = FocusRequester()
            var lastFocused = false
            var lastPosition: IntOffset? = null
            runNewSessionMosaicTest {
                val initial = setContentAndSnapshot {
                    io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                        NewSessionScreen(
                            fixture.newSession(), 20, 2, NewLineKey.ShiftEnter, { 3 }, {},
                            {
                                Column {
                                    TuiButton("First") {}
                                    Text("middle")
                                    TuiButton("Last", modifier = Modifier
                                        .focusRequester(lastRequester)
                                        .onPlaced { lastPosition = it.position }
                                        .onFocusChanged { lastFocused = it.isFocused }) {}
                                }
                            },
                        )
                    }
                }
                assertContains(initial, "fourth")
                assertEquals(IntOffset(0, 3), lastPosition)
                // Settle the editor's mount-only focus effect before making this diagnostic request.
                withTimeoutOrNull(50) { awaitSnapshot() }
                assertTrue(lastRequester.requestFocus())
                val snapshot = awaitNewSessionSnapshot("Requested target is focused and visible") {
                    lastFocused && "[Last]" in it
                }
                assertTrue(lastFocused, snapshot)
                assertEquals(IntOffset(0, 1), lastPosition)
                assertEquals(2, snapshot.lines().size, snapshot)
                assertTrue(snapshot.lines().all { it.length <= 20 }, snapshot)
            }
        } finally {
            fixture.close()
        }
    }

    test("draft and status share a centered bounded width") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            var budgetWidth = 0
            var renderedWidth = 0
            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                        NewSessionScreen(
                            fixture.newSession(), 100, 15, NewLineKey.ShiftEnter,
                            { width -> budgetWidth = width; 1 }, {},
                            { width -> renderedWidth = width; Text("STATUS") },
                        )
                    }
                }
                assertEquals(80, budgetWidth)
                assertEquals(80, renderedWidth)
                assertContains(snapshot, "Start a new session")
                assertContains(snapshot, "STATUS")
                val title = snapshot.lines().first { "Start a new session" in it }
                val status = snapshot.lines().first { "STATUS" in it }
                assertEquals(10, title.indexOf("Start a new session"))
                assertEquals(10, status.indexOf("STATUS"))
            }
        } finally {
            fixture.close()
        }
    }
    test("unmounting and returning retains the singleton Composer cursor and cwd") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val draft = fixture.newSession()
            draft.composer.update("not submitted", 4)
            val cwd = Path(fixture.rpc.root, "retained-cwd")
            draft.updateWorkingDirectory(cwd)
            draft.settings.first { it.cwd == cwd }
            var visible by mutableStateOf(true)
            runMosaicTest {
                setContentAndSnapshot {
                    io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                    if (visible) {
                        NewSessionScreen(
                            viewModel = draft,
                            columns = 40,
                            rows = 8,
                            newLineKey = NewLineKey.ShiftEnter,
                            statusBarRows = { 0 },
                            onSubmit = {},
                            statusBar = {},
                        )
                    }
                }
                }
                visible = false
                awaitSnapshot()
                assertEquals("not submitted", draft.composer.state.value.text)
                assertEquals(
                    io.github.stream29.kodex.app.agent.contract.ComposerLifecycle.Open,
                    draft.composer.state.value.lifecycle,
                )
                visible = true
                val snapshot = awaitSnapshot()
                assertContains(snapshot, "not submitted")
                assertSame(draft, fixture.newSession())
                assertEquals(4, draft.composer.state.value.cursorOffset)
                assertEquals(cwd, draft.settings.value.cwd)
                sendKeyEvent(KeyboardEvent(codepoint = 'X'.code))
                awaitSnapshot()
                assertEquals("not Xsubmitted", draft.composer.state.value.text)
                assertEquals(5, draft.composer.state.value.cursorOffset)
            }
        } finally {
            fixture.close()
        }
    }
    test("emptySessionDoesNotRenderCreationInstructions") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val composer = fixture.newSession().composer

            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                    NewSessionContent(
                        composerViewModel = composer,
                        columns = 40,
                        rows = 8,
                        newLineKey = NewLineKey.ShiftEnter,
                        onSubmit = {},
                    )
                }
                }

                assertFalse("Enter a prompt to create a session" in snapshot, snapshot)
            }
        } finally {
            fixture.close()
        }
    }

    test("longComposerUsesItsBoundedRowsAndKeepsTheCursorTailVisible") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val composer = fixture.newSession().composer
            val text = "one\ntwo\nthree\nfour"
            composer.update(text = text, cursorOffset = text.length)

            runMosaicTest {
                val snapshot = setContentAndSnapshot {
                    io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                    NewSessionContent(
                        composerViewModel = composer,
                        columns = 10,
                        rows = 4,
                        newLineKey = NewLineKey.ShiftEnter,
                        onSubmit = {},
                    )
                }
                }

                assertEquals(
                    "----------\n  two\n  three\n  four",
                    snapshot,
                )
            }
        } finally {
            fixture.close()
        }
    }

    test("submitCallbackRetainsTheExactComposerRevision") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val prompt = "Keep this first prompt."
            val composer = fixture.newSession().composer
            var submittedRevision: Long? = null

            runMosaicTest {
                setContentAndSnapshot {
                    io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                    var showNewSession by remember { mutableStateOf(true) }
                    if (showNewSession) {
                        NewSessionContent(
                            composerViewModel = composer,
                            columns = 80,
                            rows = 23,
                            newLineKey = NewLineKey.ShiftEnter,
                            onSubmit = { revision ->
                                submittedRevision = revision
                                showNewSession = false
                            },
                        )
                    } else {
                        Text("Root session")
                    }
                }
                }

                sendPasteEvent(PasteEvent(prompt))
                awaitSnapshot()
                assertEquals(prompt, composer.state.value.text)

                sendKeyEvent(KeyboardEvent(13))
                awaitSnapshot()
                assertEquals(1L, submittedRevision)
            }
        } finally {
            fixture.close()
        }
    }

    test("back to back input and send confirm revision despite a stale collected projection") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val composer = fixture.newSession().composer
            val staleProjection = object : ComposerViewModel by composer {
                override val state = MutableStateFlow(composer.state.value)
            }
            var submittedRevision: Long? = null
            var submittedText: String? = null
            runMosaicTest {
                setContentAndSnapshot {
                    io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                    NewSessionContent(
                        composerViewModel = staleProjection,
                        columns = 40, rows = 8, newLineKey = NewLineKey.ShiftEnter,
                        onSubmit = { revision ->
                            submittedRevision = revision
                            submittedText = composer.state.value.text
                            composer.update("later draft")
                        },
                    )
                }
                }
                sendPasteEvent(PasteEvent("  exact input \n "))
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                awaitSnapshot()
                assertEquals("  exact input \n ", submittedText)
                assertEquals(1L, submittedRevision)
                assertEquals("later draft", composer.state.value.text)
                assertEquals(2L, composer.state.value.revision)
            }
        } finally {
            fixture.close()
        }
    }

    for (text in listOf("", "   ", "\n", " \n first prompt \n ")) {
        test("New Session send carries raw text ${text.toCharArray().map { it.code }} without blank filtering") {
            val fixture = SessionViewModelTestFixture.create(this)
            try {
                val composer = fixture.newSession().composer
                composer.update(text)
                var revision: Long? = null
                var captured: String? = null
                var submissions by mutableStateOf(0)
                runMosaicTest {
                    setContentAndSnapshot {
                        io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                        Column {
                            NewSessionContent(
                                composer, 40, 8, NewLineKey.ShiftEnter,
                                onSubmit = {
                                    revision = it
                                    captured = composer.state.value.text
                                    submissions++
                                },
                            )
                            Text("submissions=$submissions")
                        }
                    }
                    }
                    sendKeyEvent(KeyboardEvent(codepoint = 13))
                    awaitSnapshot()
                }
                assertEquals(text, captured)
                assertEquals(composer.state.value.revision, revision)
                assertEquals(1, submissions)
            } finally {
                fixture.close()
            }
        }
    }

    test("successful submit remounts the same cleared singleton and rejects a late revision") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val draft = fixture.newSession()
            val composer = draft.composer
            val initialCwd = draft.settings.value.cwd
            draft.updateWorkingDirectory(Path(fixture.rpc.root, "submitted-cwd"))
            val revision = composer.update("first")
            var visible by mutableStateOf(true)
            runMosaicTest {
                setContentAndSnapshot {
                    io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                    if (visible) NewSessionScreen(
                        draft, 40, 8, NewLineKey.ShiftEnter, { 0 }, {}, {},
                    ) else Text("persisted Session")
                }
                }
                visible = false
                awaitSnapshot()
                assertNotNull(draft.submit(revision))
                draft.settings.first { it.cwd == initialCwd }
                assertSame(draft, fixture.newSession())
                assertSame(composer, draft.composer)
                assertEquals("", composer.state.value.text)
                assertEquals(0, composer.state.value.cursorOffset)
                visible = true
                val reset = awaitSnapshot()
                assertFalse("first" in reset, reset)
                sendPasteEvent(PasteEvent("second draft"))
                awaitSnapshot()
                assertNull(draft.submit(revision))
                assertEquals("second draft", composer.state.value.text)
                assertEquals(12, composer.state.value.cursorOffset)
            }
        } finally {
            fixture.close()
        }
    }

    test("composerStateEchoPreservesTheFrontendCursorAndHistory") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val composer = fixture.newSession().composer

            runMosaicTest {
                setContentAndSnapshot {
                    io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                    NewSessionContent(
                        composerViewModel = composer,
                        columns = 20,
                        rows = 8,
                        newLineKey = NewLineKey.ShiftEnter,
                        onSubmit = {},
                    )
                }
                }

                sendPasteEvent(PasteEvent("abc"))
                awaitSnapshot()
                repeat(2) {
                    sendKeyEvent(
                        KeyboardEvent(
                            codepoint = KeyboardEvent.Left,
                        ),
                    )
                    awaitSnapshot()
                }
                sendKeyEvent(KeyboardEvent(codepoint = 'X'.code))
                awaitSnapshot()

                assertEquals("aXbc", composer.state.value.text)
                assertEquals(2, composer.state.value.cursorOffset)

                sendKeyEvent(
                    KeyboardEvent(
                        codepoint = 'z'.code,
                        modifiers = KeyboardEvent.ModifierCtrl,
                    ),
                )
                awaitSnapshot()
                assertEquals("abc", composer.state.value.text)
                assertEquals(1, composer.state.value.cursorOffset)

                sendKeyEvent(KeyboardEvent(codepoint = 'Y'.code))
                awaitSnapshot()
                assertEquals("aYbc", composer.state.value.text)

                composer.update(text = "external", cursorOffset = 8)
                awaitSnapshot()
                sendKeyEvent(
                    KeyboardEvent(
                        codepoint = 'z'.code,
                        modifiers = KeyboardEvent.ModifierCtrl,
                    ),
                )
                awaitSnapshot()
                assertEquals("external", composer.state.value.text)
                assertEquals(8, composer.state.value.cursorOffset)
            }
        } finally {
            fixture.close()
        }
    }
}

internal suspend fun runNewSessionMosaicTest(block: suspend TestMosaic<String>.() -> Unit) {
    runMosaicTest {
        try {
            block()
        } finally {
            cancel()
        }
    }
}

internal suspend fun TestMosaic<String>.awaitNewSessionSnapshot(
    expectation: String,
    diagnostics: () -> String = { "" },
    matches: (String) -> Boolean,
): String {
    var snapshot = draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
    val matched = withTimeoutOrNull(2_000) {
        while (!matches(snapshot)) {
            withTimeoutOrNull(50) { awaitSnapshot() }?.let { snapshot = it }
        }
        true
    } ?: false
    assertTrue(matched, "$expectation; ${diagnostics()}\n$snapshot")
    return snapshot
}
