package io.github.stream29.kodex.cli.sessionrename

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Text
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.sessionrename.createSessionRenameViewModel
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameDependencies
import io.github.stream29.kodex.cli.components.TuiPopupHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.test.*

val sessionRenamePopupTest by testSuite {
    test("clearing the final character before the next frame does not submit or notify") {
        var calls = 0
        var notifications = 0
        var frame by mutableStateOf(0)
        val child = createSessionRenameViewModel("X", SessionRenameDependencies { calls++ })
        try {
            runMosaicTest {
                setContentAndSnapshot {
                    io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                    Box {
                        TuiPopupHost(modifier = Modifier.width(90).height(24)) {
                            Text("frame=$frame")
                            SessionRenamePopup(child, {}, { notifications++ })
                        }
                    }
                }
                }
                sendKeyEvent(KeyboardEvent(127))
                sendKeyEvent(KeyboardEvent(13))
                frame++
                awaitSnapshot()
                assertEquals("", child.draftName.value)
                assertEquals(0, calls)
                assertEquals(0, notifications)
                assertTrue(child.isActive)
            }
        } finally { child.close() }
    }
    for (presentation in SessionRenamePresentation.entries) {
        test("$presentation retains consecutive cursor edits before the draft echo renders") {
            val vm = createSessionRenameViewModel("abc", SessionRenameDependencies {})
            try {
                runMosaicTest {
                    setContentAndSnapshot {
                        io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                        Box {
                            TuiPopupHost(modifier = Modifier.width(90).height(24)) {
                                SessionRenamePopup(vm, {}, {}, presentation)
                            }
                        }
                    }
                    }
                    repeat(2) {
                        sendKeyEvent(KeyboardEvent(KeyboardEvent.Left))
                        awaitSnapshot()
                    }
                    sendKeyEvent(KeyboardEvent('X'.code))
                    repeat(4) { if (vm.draftName.value == "abc") awaitSnapshot() }
                    assertEquals("aXbc", vm.draftName.value)
                }
            } finally { vm.close() }
        }
        test("$presentation snapshots the submitted name before subsequent input") {
            val started = CompletableDeferred<String>()
            val finish = CompletableDeferred<Unit>()
            val vm = createSessionRenameViewModel("captured", SessionRenameDependencies {
                started.complete(it)
                finish.await()
            })
            var drawToken by mutableStateOf(0)
            try {
                runMosaicTest {
                    setContentAndSnapshot {
                        io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                        Box {
                            TuiPopupHost(modifier = Modifier.width(90).height(24)) {
                                Text("draw=$drawToken")
                                SessionRenamePopup(vm, {}, {}, presentation)
                            }
                        }
                    }
                    }
                    sendKeyEvent(KeyboardEvent(codepoint = 13))
                    sendKeyEvent(KeyboardEvent(codepoint = 'X'.code))
                    drawToken++
                    awaitSnapshot()
                    assertEquals("captured", withTimeout(1_000) { started.await() })
                    assertEquals("capturedX", vm.draftName.value)
                    finish.complete(Unit)
                }
            } finally {
                finish.complete(Unit)
                vm.close()
            }
        }
        test("$presentation binds edits to the component and submits on plain Enter") {
            val submitted = CompletableDeferred<String>()
            val notified = CompletableDeferred<Unit>()
            val vm = createSessionRenameViewModel("captured", SessionRenameDependencies {
                submitted.complete(it)
            })
            var visible by mutableStateOf(true)
            try {
                runMosaicTest {
                    val snapshot = setContentAndSnapshot {
                        io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                        Box {
                            TuiPopupHost(modifier = Modifier.width(90).height(24)) {
                                if (visible) SessionRenamePopup(
                                    vm,
                                    onDismissRequest = { visible = false },
                                    onSubmitted = { notified.complete(Unit); visible = false },
                                    presentation = presentation,
                                )
                            }
                        }
                    }
                    }
                    assertTrue("Rename session" in snapshot, snapshot)
                    assertTrue("captured" in snapshot, snapshot)
                    assertEquals(presentation == SessionRenamePresentation.Labeled, "Session name" in snapshot)
                    assertFalse("[Rename]" in snapshot, "No redundant Rename action: $snapshot")
                    sendKeyEvent(KeyboardEvent(13, modifiers = KeyboardEvent.ModifierShift))
                    assertFalse(submitted.isCompleted, "Shift+Enter must not submit")
                    sendKeyEvent(KeyboardEvent(codepoint = 'X'.code))
                    awaitSnapshot()
                    assertFalse(submitted.isCompleted, "Editing alone must not submit")
                    sendKeyEvent(KeyboardEvent(codepoint = 13))
                    awaitSnapshot()
                    assertEquals("capturedX", withTimeout(1_000) { submitted.await() })
                    withTimeout(1_000) { notified.await() }
                    // Completion requests unmount; measurement may have drawn an earlier edit frame.
                    repeat(4) { if (vm.isActive) awaitSnapshot() }
                    assertEquals("capturedX", vm.draftName.value)
                    assertFalse(vm.isActive, "Unmount after completion must close the child")
                }
            } finally {
                vm.close()
            }
        }
        test("$presentation never submits a blank draft and closes on disposal") {
            var calls = 0
            val vm = createSessionRenameViewModel("   ", SessionRenameDependencies { calls++ })
            var visible by mutableStateOf(true)
            try {
                runMosaicTest {
                    setContentAndSnapshot {
                        io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                        Box {
                            TuiPopupHost(modifier = Modifier.width(90).height(24)) {
                                if (visible) SessionRenamePopup(vm, {}, {}, presentation)
                            }
                        }
                    }
                    }
                    sendKeyEvent(KeyboardEvent(codepoint = 13))
                    sendKeyEvent(KeyboardEvent(codepoint = 'X'.code))
                    awaitSnapshot()
                    assertEquals(0, calls)
                    visible = false
                    awaitSnapshot()
                    assertFalse(vm.isActive)
                }
            } finally {
                vm.close()
            }
        }
    }
    test("replacing a child suppresses the old operation's completion callback") {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val old = createSessionRenameViewModel("old", SessionRenameDependencies {
            started.complete(Unit)
            finish.await()
            completed.complete(Unit)
        })
        val next = createSessionRenameViewModel("next", SessionRenameDependencies {})
        var current by mutableStateOf(old)
        var drawToken by mutableStateOf(0)
        var callbacks = 0
        try {
            runMosaicTest {
                setContentAndSnapshot {
                    io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                    Box {
                        TuiPopupHost(modifier = Modifier.width(90).height(24)) {
                            Text("draw=$drawToken")
                            SessionRenamePopup(current, {}, { callbacks++ })
                        }
                    }
                }
                }
                sendKeyEvent(KeyboardEvent(codepoint = 13))
                drawToken++
                awaitSnapshot()
                withTimeout(1_000) { started.await() }
                current = next
                repeat(5) { if (old.isActive) awaitSnapshot() }
                assertFalse(old.isActive)
                finish.complete(Unit)
                withTimeout(1_000) { completed.await() }
                assertEquals(0, callbacks)
                assertTrue(next.isActive)
            }
        } finally {
            finish.complete(Unit)
            old.close()
            next.close()
        }
    }
}
