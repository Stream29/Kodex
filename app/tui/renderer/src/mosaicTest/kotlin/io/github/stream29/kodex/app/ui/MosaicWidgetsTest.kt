package io.github.stream29.kodex.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.onPreviewKeyEvent
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.ui.Box
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.cli.components.TuiButton
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

val mosaicWidgetsTest by testSuite {
    test("editor focus capability targets its exact mounted input and rebinds after unmount") {
        var text by mutableStateOf("Draft")
        var cursor by mutableStateOf(text.length)
        var mounted by mutableStateOf(true)
        var handle: UiEditorFocusHandle? = null
        runMosaicTest {
            setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                val focus = rememberUiEditorFocusHandle()
                handle = focus
                Box(Modifier.width(40).height(8)) {
                    UiColumn {
                        UiAction("Elsewhere", {})
                        if (mounted) WithUiEditorFocus(focus) {
                            UiEditor(text, cursor, { value, offset -> text = value; cursor = offset; null }, { _, _ -> })
                        } else UiText("Editor unmounted")
                    }
                }
            }
            }
            val focus = assertNotNull(handle)
            assertTrue(focus.requestFocus())
            sendKeyEvent(KeyboardEvent('X'.code))
            awaitContaining("DraftX")
            mounted = false
            awaitContaining("Editor unmounted")
            assertFalse(focus.requestFocus())
            text = "New"
            cursor = text.length
            mounted = true
            awaitContaining("New")
            assertTrue(focus.requestFocus())
            sendKeyEvent(KeyboardEvent('Y'.code))
            awaitContaining("NewY")
            assertEquals("NewY", text)
        }
        assertFalse(assertNotNull(handle).requestFocus())
    }
    test("disabled leaf rejects focus requests without routing input to it") {
        var text by mutableStateOf("Draft")
        var cursor by mutableStateOf(text.length)
        var enabled by mutableStateOf(false)
        var barrier by mutableStateOf(0)
        var handle: UiEditorFocusHandle? = null
        val fallback = FocusRequester()
        runMosaicTest {
            setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                val focus = rememberUiEditorFocusHandle()
                handle = focus
                Box(Modifier.width(40).height(8).onPreviewKeyEvent {
                    if (it.key == "F12") { barrier++; true } else false
                }) {
                    UiColumn {
                        TuiButton("Elsewhere", focusRequester = fallback, onClick = {})
                        WithUiEditorFocus(focus) {
                            UiEditor(text, cursor, { value, offset -> text = value; cursor = offset; null }, { _, _ -> }, enabled = enabled)
                        }
                        UiText("enabled=$enabled barrier=$barrier")
                    }
                }
            }
            }
            val focus = assertNotNull(handle)
            assertTrue(fallback.requestFocus())
            assertFalse(focus.requestFocus())
            sendKeyEvent(KeyboardEvent('X'.code))
            sendKeyEvent(KeyboardEvent(KeyboardEvent.F12))
            awaitContaining("barrier=1")
            assertEquals("Draft", text)
            enabled = true
            awaitContaining("enabled=true")
            assertTrue(focus.requestFocus())
            sendKeyEvent(KeyboardEvent('X'.code))
            awaitContaining("DraftX")
            enabled = false
            awaitContaining("enabled=false")
            assertTrue(fallback.requestFocus())
            assertFalse(focus.requestFocus())
            sendKeyEvent(KeyboardEvent('Y'.code))
            sendKeyEvent(KeyboardEvent(KeyboardEvent.F12))
            awaitContaining("barrier=2")
            assertEquals("DraftX", text)
        }
    }
    test("neutral editor and action preserve text cursor and activation ownership") {
        var text by mutableStateOf("Draft")
        var cursor by mutableStateOf(text.length)
        var submitted by mutableStateOf(0)
        var clicked by mutableStateOf(0)
        runMosaicTest {
            setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                Box(Modifier.width(40).height(8)) {
                    UiColumn {
                        UiEditor(text, cursor, { changed, offset -> text = changed; cursor = offset; null }, { _, _ -> submitted++ }, autoFocus = true)
                        UiAction("Apply", onClick = { clicked++ })
                        UiText("submitted=$submitted clicked=$clicked")
                    }
                }
            }
            }
            sendKeyEvent(KeyboardEvent(codepoint = 'X'.code))
            val edited = awaitContaining("DraftX")
            assertTrue("DraftX" in edited, edited)
            assertEquals("DraftX", text)
            assertEquals(6, cursor)
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitContaining("submitted=1")
            assertEquals(1, submitted)
            assertEquals("DraftX", text)
            sendKeyEvent(KeyboardEvent(codepoint = 9))
            awaitSnapshot()
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            awaitContaining("clicked=1")
            assertEquals(1, clicked)
        }
    }
    test("read-only editor does not mutate text or submit") {
        var mutations = 0
        var submissions = 0
        var cursor by mutableStateOf(9)
        runMosaicTest {
            setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                Box(Modifier.width(40).height(8)) {
                    UiEditor(
                        "Read only", cursor, { changed, offset ->
                            if (changed != "Read only") mutations++
                            cursor = offset
                            null
                        }, { _, _ -> submissions++ }, readOnly = true, autoFocus = true,
                    )
                }
            }
            }
            sendKeyEvent(KeyboardEvent(codepoint = 'X'.code))
            sendKeyEvent(KeyboardEvent(codepoint = 13))
            sendKeyEvent(KeyboardEvent(codepoint = KeyboardEvent.Left))
            awaitSnapshot()
            assertEquals(0, mutations)
            assertEquals(0, submissions)
            assertEquals(8, cursor)
        }
    }
    for (policy in UiSubmitKey.entries) {
        test("newline and submit are distinct for $policy") {
            var text by mutableStateOf("one")
            var cursor by mutableStateOf(text.length)
            var submits by mutableStateOf(0)
            runMosaicTest {
                setContentAndSnapshot {
                    io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                    Box(Modifier.width(60).height(12)) {
                        UiColumn {
                            UiEditor(
                                text, cursor, { changed, offset -> text = changed; cursor = offset; null }, { _, _ -> submits++ },
                                autoFocus = true, submitKey = policy,
                            )
                            UiText("value=${text.replace("\n", "|")} submits=$submits")
                        }
                    }
                }
                }
                val newlineModifiers = if (policy == UiSubmitKey.Enter) KeyboardEvent.ModifierShift else 0
                val submitModifiers = if (policy == UiSubmitKey.CtrlEnter) KeyboardEvent.ModifierCtrl else 0
                sendKeyEvent(KeyboardEvent(13, modifiers = newlineModifiers))
                awaitContaining("value=one| submits=0")
                sendKeyEvent(KeyboardEvent(13, modifiers = submitModifiers))
                awaitContaining("value=one| submits=1")
                val ignored = if (policy == UiSubmitKey.Enter) {
                    KeyboardEvent.ModifierCtrl or KeyboardEvent.ModifierShift
                } else KeyboardEvent.ModifierShift
                sendKeyEvent(KeyboardEvent(13, modifiers = ignored))
                sendKeyEvent(KeyboardEvent('X'.code))
                awaitContaining("value=one|X submits=1")
                assertEquals("one\nX", text)
            }
        }
    }
}

private suspend fun TestMosaic<String>.awaitContaining(expected: String): String {
    var latest = ""
    repeat(4) {
        latest = awaitSnapshot()
        if (expected in latest) return latest
    }
    error("Expected $expected in snapshot: $latest")
}
