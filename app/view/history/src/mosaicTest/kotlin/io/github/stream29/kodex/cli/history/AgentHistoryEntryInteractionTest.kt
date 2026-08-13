package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.focus.focusable
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.SnapshotStrategy
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.unit.IntOffset
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.history.contract.AgentHistoryEntry
import io.github.stream29.kodex.app.history.contract.AgentHistoryEntryKey
import io.github.stream29.kodex.openai.ContentItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val ansiSnapshots = SnapshotStrategy { mosaic ->
    mosaic.draw().render(AnsiLevel.TRUECOLOR, supportsKittyUnderlines = false)
}

val agentHistoryEntryInteractionTest by testSuite {
    test("the complete multiline entry has no focus highlight and supports secondary actions") {
        var callbackCount by mutableStateOf(0)
        var capturedGeneration: Long? = null
        var capturedIndex: Int? = null
        var capturedAnchorPlaced = false
        var capturedClickPosition: IntOffset? = IntOffset(x = -1, y = -1)
        val entry = AgentHistoryEntry(
            key = AgentHistoryEntryKey(primaryStorageIndex = 17),
            event = StableCleanEvent.AssistantMessage(
                listOf(ContentItem.OutputText("first line\nsecond line")),
            ),
        )

        runMosaicTest(snapshotStrategy = ansiSnapshots) {
            val initial = setContentAndSnapshot {
                Column(modifier = Modifier.width(40)) {
                    StoredHistoryEntry(
                        entry = entry,
                        generation = 4,
                        shellSessions = EmptyAgentShellSessionRegistry,
                        onOpenContextMenu = { generation, storageIndex, anchor, clickPosition ->
                            capturedGeneration = generation
                            capturedIndex = storageIndex
                            capturedAnchorPlaced = anchor.isPlaced
                            capturedClickPosition = clickPosition
                            callbackCount++
                        },
                    )
                    Text(
                        value = "callbacks=$callbackCount",
                        modifier = Modifier.focusable(autoFocus = true),
                    )
                }
            }

            sendMouseEvent(MouseEvent(6, 2, MouseEvent.Type.Press, MouseEvent.Button.Left))
            sendMouseEvent(MouseEvent(6, 2, MouseEvent.Type.Release))
            assertEquals(initial, awaitSnapshot())

            sendMouseEvent(MouseEvent(6, 2, MouseEvent.Type.Press, MouseEvent.Button.Right))
            sendMouseEvent(MouseEvent(6, 2, MouseEvent.Type.Release))
            awaitSnapshot()

            assertEquals(1, callbackCount)
            assertEquals(4, capturedGeneration)
            assertEquals(17, capturedIndex)
            assertTrue(capturedAnchorPlaced)
            assertEquals(IntOffset(x = 6, y = 2), capturedClickPosition)

            sendKeyEvent(
                KeyboardEvent(
                    codepoint = KeyboardEvent.F10,
                    modifiers = KeyboardEvent.ModifierShift,
                ),
            )
            awaitSnapshot()

            assertEquals(2, callbackCount)
            assertEquals(null, capturedClickPosition)
        }
    }

    test("a nested tool control remains expandable and keeps the row secondary action") {
        var callbackCount by mutableStateOf(0)
        val entry = AgentHistoryEntry(
            key = AgentHistoryEntryKey(primaryStorageIndex = 23),
            event = StableTextToolEvent(
                callId = "call",
                name = "demo",
                arguments = JsonObject(emptyMap()),
                result = "done",
                success = true,
            ),
        )

        runMosaicTest {
            val collapsed = setContentAndSnapshot {
                Column(modifier = Modifier.width(40)) {
                    StoredHistoryEntry(
                        entry = entry,
                        generation = 8,
                        shellSessions = EmptyAgentShellSessionRegistry,
                        onOpenContextMenu = { _, _, _, _ -> callbackCount++ },
                    )
                    Text("callbacks=$callbackCount")
                }
            }
            assertTrue("> demo" in collapsed)

            sendMouseEvent(MouseEvent(0, 0, MouseEvent.Type.Press, MouseEvent.Button.Left))
            sendMouseEvent(MouseEvent(0, 0, MouseEvent.Type.Release))
            val expanded = awaitSnapshot()
            assertTrue("v demo" in expanded)

            sendMouseEvent(MouseEvent(0, 2, MouseEvent.Type.Press, MouseEvent.Button.Right))
            sendMouseEvent(MouseEvent(0, 2, MouseEvent.Type.Release))
            awaitSnapshot()

            assertEquals(1, callbackCount)
        }
    }
}

private object EmptyAgentShellSessionRegistry : AgentShellSessionRegistry {
    override val activeSessions =
        MutableStateFlow<Map<Int, AgentShellSession>>(emptyMap())
}
