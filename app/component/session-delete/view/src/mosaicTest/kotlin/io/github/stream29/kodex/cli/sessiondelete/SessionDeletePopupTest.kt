package io.github.stream29.kodex.cli.sessiondelete

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
import io.github.stream29.kodex.app.sessiondelete.createSessionDeleteViewModel
import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteDependencies
import io.github.stream29.kodex.cli.components.TuiPopupHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.test.*

val sessionDeletePopupTest by testSuite {
    for (title in listOf("captured title", null)) {
        test("renders $title and Cancel starts focused without deleting") {
            var calls = 0
            var dismissals = 0
            var visible by mutableStateOf(true)
            val vm = createSessionDeleteViewModel(41, title, SessionDeleteDependencies { calls++; true })
            try {
                runMosaicTest {
                    val snapshot = setContentAndSnapshot {
                        Box {
                            TuiPopupHost(modifier = Modifier.width(90).height(24)) {
                                if (visible) SessionDeletePopup(
                                    vm,
                                    onDismissRequest = { dismissals++; visible = false },
                                    onResult = { error("Cancel must not report a deletion") },
                                )
                            }
                        }
                    }
                    assertTrue("Delete ${title ?: "Session 41"}?" in snapshot, snapshot)
                    assertTrue("persisted session" in snapshot, snapshot)
                    assertTrue("[Cancel]" in snapshot, snapshot)
                    assertTrue("[Delete]" in snapshot, snapshot)
                    sendKeyEvent(KeyboardEvent(codepoint = 13))
                    awaitSnapshot()
                    assertEquals(1, dismissals)
                    assertEquals(0, calls)
                    assertFalse(vm.isActive)
                }
            } finally {
                vm.close()
            }
        }
    }
    for (result in listOf(true, false)) {
        test("reports $result unchanged and leaves dismissal to the host") {
            val reported = CompletableDeferred<Boolean>()
            val calls = mutableListOf<Int>()
            val vm = createSessionDeleteViewModel(41, null, SessionDeleteDependencies { calls += it; result })
            var visible by mutableStateOf(true)
            var resultMarker by mutableStateOf("pending")
            try {
                runMosaicTest {
                    setContentAndSnapshot {
                        Box {
                            TuiPopupHost(modifier = Modifier.width(90).height(24)) {
                                Text("result=$resultMarker")
                                if (visible) SessionDeletePopup(vm, {}, {
                                    reported.complete(it)
                                    resultMarker = it.toString()
                                })
                            }
                        }
                    }
                    sendKeyEvent(KeyboardEvent(codepoint = 9))
                    sendKeyEvent(KeyboardEvent(codepoint = 13))
                    awaitSnapshot()
                    assertEquals(result, withTimeout(1_000) { reported.await() })
                    assertEquals(listOf(41), calls)
                    assertTrue(vm.isActive)
                    visible = false
                    repeat(5) { if (vm.isActive) awaitSnapshot() }
                    assertFalse(vm.isActive)
                }
            } finally {
                vm.close()
            }
        }
    }
    test("replacing a confirmation cannot apply its late result to the new child") {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val old = createSessionDeleteViewModel(41, "old", SessionDeleteDependencies {
            started.complete(Unit)
            finish.await()
            completed.complete(Unit)
            true
        })
        val next = createSessionDeleteViewModel(42, "next", SessionDeleteDependencies { false })
        var current by mutableStateOf(old)
        var drawToken by mutableStateOf(0)
        var callbacks = 0
        try {
            runMosaicTest {
                setContentAndSnapshot {
                    Box {
                        TuiPopupHost(modifier = Modifier.width(90).height(24)) {
                            Text("draw=$drawToken")
                            SessionDeletePopup(current, {}, { callbacks++ })
                        }
                    }
                }
                sendKeyEvent(KeyboardEvent(codepoint = 9))
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
