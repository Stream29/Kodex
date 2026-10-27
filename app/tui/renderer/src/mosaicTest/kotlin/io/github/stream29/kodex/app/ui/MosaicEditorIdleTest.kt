package io.github.stream29.kodex.app.ui

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.Mosaic
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.terminal.PasteEvent
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.testing.TestTerminal
import com.jakewharton.mosaic.ui.Layout
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay

val mosaicEditorIdleTest by testSuite {
    test("real editor root settles after input layout focus and revision changes") {
        coroutineScope {
            var width by mutableIntStateOf(24)
            var maximumHeight by mutableIntStateOf(12)
            var text by mutableStateOf("A你e\u0301👩‍💻Z")
            var cursor by mutableIntStateOf(text.length)
            var revision by mutableStateOf(10L)
            var readOnly by mutableStateOf(false)
            var enabled by mutableStateOf(true)
            var multiline by mutableStateOf(true)
            var label by mutableStateOf("")
            var maximumLines by mutableIntStateOf(6)
            var submitKey by mutableStateOf(UiSubmitKey.Enter)
            var callbackVersion by mutableIntStateOf(0)
            var acceptedRevision = 10L
            var localText = text
            var localCursor = cursor
            var changedBy = -1
            var submittedBy = -1
            var submitted: Pair<String, Int>? = null
            var clicks = 0
            var focus: UiEditorFocusHandle? = null
            val terminal = TestTerminal()
            val clock = BroadcastFrameClock()
            var frames = 0
            var measures = 0
            var draws = 0
            var rendered = ""
            val mosaic = Mosaic(
                coroutineContext = currentCoroutineContext() + clock,
                terminal = terminal,
                onDraw = {
                    draws++
                    rendered = it.draw().render(AnsiLevel.NONE, false)
                },
            )

            suspend fun drive(count: Int) {
                repeat(count) {
                    assertTrue(clock.hasAwaiters, "No root frame listener at frame $frames")
                    clock.sendFrame(++frames * 1_000_000L)
                    delay(1.milliseconds)
                }
            }

            suspend fun assertSettled(expected: String) {
                drive(8)
                assertTrue(expected in rendered, "Expected <$expected> in <$rendered>")
                val settledMeasures = measures
                val settledDraws = draws
                val settledPixels = rendered
                drive(100)
                assertEquals(settledMeasures, measures, "Editor root kept measuring at idle")
                assertEquals(settledDraws, draws, "Editor root kept drawing identical idle frames")
                assertEquals(settledPixels, rendered)
            }

            try {
                mosaic.setContent {
                    WithMosaicComponentRenderers {
                        val handle = rememberUiEditorFocusHandle()
                        focus = handle
                        Layout(content = {
                            UiColumn {
                                UiText("Header", maximumLines = 1)
                                WithUiEditorFocus(handle) {
                                    val version = callbackVersion
                                    UiEditor(
                                        text, cursor,
                                        onChange = { changed, offset ->
                                            changedBy = version
                                            localText = changed
                                            localCursor = offset
                                            // Deliberately delay the owner's echo to exercise revision rejection.
                                            ++acceptedRevision
                                        },
                                        onSubmit = { value, offset ->
                                            submittedBy = version
                                            submitted = value to offset
                                        },
                                        autoFocus = true,
                                        enabled = enabled,
                                        multiline = multiline,
                                        label = label,
                                        maximumLines = maximumLines,
                                        readOnly = readOnly,
                                        submitKey = submitKey,
                                        revision = revision,
                                    )
                                }
                                UiAction("Elsewhere", onClick = { clicks++ })
                            }
                        }) { measurables, constraints ->
                            measures++
                            // These snapshot reads invalidate layout without recomposing its content.
                            val placeable = measurables.single().measure(
                                constraints.copy(maxWidth = width, maxHeight = maximumHeight),
                            )
                            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                        }
                    }
                }
                assertSettled("> A你e\u0301👩‍💻Z")
                assertTrue(assertNotNull(focus).requestFocus())

                terminal.events.send(PasteEvent("中👩‍💻"))
                assertSettled("> A你e\u0301👩‍💻Z中👩‍💻")
                assertEquals("A你e\u0301👩‍💻Z中👩‍💻", localText)
                assertEquals(localText.length, localCursor)

                text = "stale echo"
                cursor = 0
                revision = 9
                assertSettled("> A你e\u0301👩‍💻Z中👩‍💻")
                terminal.events.send(KeyboardEvent(13))
                assertSettled("> A你e\u0301👩‍💻Z中👩‍💻")
                assertEquals(localText to localCursor, submitted)

                text = "abcdefghij"
                cursor = text.length
                revision = ++acceptedRevision
                assertSettled("> abcdefghij")
                width = 8
                assertSettled("> abcdef\n  ghij")
                val measuresBeforeHeightChange = measures
                maximumHeight = 10
                assertSettled("> abcdef\n  ghij")
                assertTrue(measures > measuresBeforeHeightChange, "Constraint-only height change was lost")
                maximumLines = 1
                assertSettled("  ghij")
                maximumLines = 6
                width = 24
                assertSettled("> abcdefghij")

                callbackVersion = 1
                val drawsBeforeTerminalChange = draws
                terminal.state.size.value = Terminal.Size(20, 24)
                assertSettled("> abcdefghij")
                assertTrue(draws > drawsBeforeTerminalChange, "Terminal columns did not reach the renderer")
                terminal.events.send(KeyboardEvent('X'.code))
                assertSettled("> abcdefghijX")
                assertEquals(1, changedBy)
                terminal.events.send(KeyboardEvent(13))
                assertSettled("> abcdefghijX")
                assertEquals(1, submittedBy)
                assertEquals("abcdefghijX" to 11, submitted)

                readOnly = true
                assertSettled("> abcdefghijX")
                terminal.events.send(PasteEvent("blocked"))
                terminal.events.send(KeyboardEvent('Y'.code))
                terminal.events.send(KeyboardEvent(13))
                terminal.events.send(KeyboardEvent(KeyboardEvent.Left))
                assertSettled("> abcdefghijX")
                assertEquals("abcdefghijX", localText)
                assertEquals(10, localCursor)

                readOnly = false
                submitKey = UiSubmitKey.CtrlEnter
                assertSettled("> abcdefghijX")
                terminal.events.send(KeyboardEvent(13, modifiers = KeyboardEvent.ModifierCtrl))
                assertSettled("> abcdefghijX")
                assertEquals("abcdefghijX" to 10, submitted)

                terminal.events.send(KeyboardEvent(9))
                drive(8)
                terminal.events.send(KeyboardEvent(13))
                drive(8)
                assertEquals(1, clicks, "Tab must transfer activation to the sibling action")
                terminal.events.send(MouseEvent(2, 1, MouseEvent.Type.Press, MouseEvent.Button.Left))
                terminal.events.send(MouseEvent(2, 1, MouseEvent.Type.Release, MouseEvent.Button.Left))
                terminal.events.send(PasteEvent("你"))
                assertSettled("> 你abcdefghijX")
                assertEquals("你abcdefghijX", localText)
                assertEquals(1, localCursor)

                label = "Label"
                assertSettled("Label\n你abcdefghijX")
                text = "abc\ndef"
                cursor = text.length
                revision = ++acceptedRevision
                assertSettled("Label\nabc\ndef")
                multiline = false
                assertSettled("Label\ndef")
                multiline = true
                assertSettled("Label\nabc\ndef")

                enabled = false
                assertSettled("Label\nabc\ndef")
                assertTrue(!assertNotNull(focus).requestFocus())
                val beforeDisabled = localText
                terminal.events.send(PasteEvent("blocked"))
                terminal.events.send(KeyboardEvent('Y'.code))
                assertSettled("Label\nabc\ndef")
                assertEquals(beforeDisabled, localText)
                enabled = true
                assertSettled("Label\nabc\ndef")
                assertTrue(assertNotNull(focus).requestFocus())
                terminal.events.send(PasteEvent("你"))
                assertSettled("Label\nabc\ndef你")
                assertEquals("abc\ndef你", localText)
            } finally {
                mosaic.cancel()
                terminal.close()
            }
        }
    }
}
