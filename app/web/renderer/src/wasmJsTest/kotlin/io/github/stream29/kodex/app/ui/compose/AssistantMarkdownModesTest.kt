package io.github.stream29.kodex.app.ui.compose

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshotFlow
import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val assistantMarkdownModesTest by testSuite {
    test("stored choices are per owner, generation and index") {
        val firstOwner = AssistantMarkdownModes()
        val secondOwner = AssistantMarkdownModes()
        firstOwner.toggleStored(7, 2)
        assertTrue(firstOwner.storedIsRaw(7, 2))
        assertFalse(firstOwner.storedIsRaw(7, 3))
        assertFalse(firstOwner.storedIsRaw(8, 2))
        assertFalse(secondOwner.storedIsRaw(7, 2))
        firstOwner.retainGeneration(8)
        assertFalse(firstOwner.storedIsRaw(7, 2))
        assertFalse(firstOwner.storedIsRaw(8, 2))
    }
    test("stream choice uses exact identity and never carries to a committed row") {
        val modes = AssistantMarkdownModes()
        val stream = Any()
        val replacement = Any()
        modes.reportStreamEligibility(3, stream, false)
        modes.toggleStream(3, stream)
        assertFalse(modes.streamIsRaw(3, stream))
        modes.reportStreamEligibility(3, stream, true)
        modes.toggleStream(3, stream)
        assertTrue(modes.streamIsRaw(3, stream))
        assertFalse(modes.streamIsRaw(3, replacement))
        assertFalse(modes.storedIsRaw(3, 9))
        modes.retainStream(3, replacement)
        assertFalse(modes.streamIsRaw(3, stream))
        modes.reportStreamEligibility(3, replacement, true)
        modes.toggleStream(3, replacement)
        modes.retainGeneration(4)
        assertFalse(modes.streamIsRaw(3, replacement))
    }
    test("new stream eligibility is observable even before its identity was known") {
        coroutineScope {
            val modes = AssistantMarkdownModes()
            val stream = Any()
            val observed = mutableListOf<Boolean>()
            val watch = launch(start = CoroutineStart.UNDISPATCHED) {
                snapshotFlow { modes.streamIsEligible(9, stream) }.take(2).toList(observed)
            }
            modes.reportStreamEligibility(9, stream, true)
            Snapshot.sendApplyNotifications()
            withTimeout(1_000) { watch.join() }
            assertEquals(listOf(false, true), observed)
        }
    }
}
