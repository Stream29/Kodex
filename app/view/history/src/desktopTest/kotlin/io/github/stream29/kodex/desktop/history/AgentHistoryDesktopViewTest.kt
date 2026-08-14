package io.github.stream29.kodex.desktop.history

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import io.github.stream29.kodex.app.history.contract.AgentHistoryEntry
import io.github.stream29.kodex.app.history.contract.AgentHistoryEntryKey
import io.github.stream29.kodex.desktop.components.DesktopComposer
import io.github.stream29.kodex.desktop.components.DesktopComposerSubmitKey
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.PlanItemArg
import io.github.stream29.kodex.openai.StepStatus
import io.github.stream29.kodex.openai.UpdatePlanArgs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

public class AgentHistoryDesktopViewTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    public fun committedMessageIsOneFocusableSecondaryActionSurface(): Unit =
        runDesktopComposeUiTest {
            val focusRequester = FocusRequester()
            setContent {
                MaterialTheme {
                    CommittedHistoryDesktopRow(
                        entry = AgentHistoryEntry(
                            key = AgentHistoryEntryKey(primaryStorageIndex = 17),
                            event = StableCleanEvent.AssistantMessage(
                                listOf(ContentItem.OutputText("first line\nsecond line")),
                            ),
                        ),
                        generation = 4,
                        focusRequester = focusRequester,
                        uiState = AgentHistoryDesktopUiState(),
                        shellSessions = EmptyAgentShellSessionRegistry,
                        canActOnHistory = true,
                        onRequestRevert = {},
                        onRequestFork = {},
                        modifier = Modifier.width(480.dp).testTag("history-entry"),
                    )
                }
            }

            runOnIdle { focusRequester.requestFocus() }
            onNodeWithTag("history-entry").assertIsFocused()
            onNodeWithText("Assistant").assertTextEquals("Assistant")
            onNodeWithText("first line\nsecond line").assertExists()

            onNodeWithTag("history-entry").performKeyInput {
                keyDown(Key.ShiftLeft)
                pressKey(Key.F10)
                keyUp(Key.ShiftLeft)
            }
            onNodeWithText("Revert to here").assertExists()
            onNodeWithText("Fork from here").assertExists()
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    public fun toolUsesCliSummaryAndNestedCollapsedSections(): Unit =
        runDesktopComposeUiTest {
            setContent {
                MaterialTheme {
                    StableEventDesktopView(
                        event = StableTextToolEvent(
                            callId = "call",
                            name = "demo",
                            arguments = JsonObject(emptyMap()),
                            result = "done",
                            success = true,
                        ),
                        shellSessions = EmptyAgentShellSessionRegistry,
                        uiState = AgentHistoryDesktopUiState(),
                        expansionPrefix = "entry",
                    )
                }
            }

            onNodeWithText("> demo").assertExists()
            onNodeWithText("> demo").performClick()
            onNodeWithText("Tool: demo").assertExists()
            onNodeWithText("> Arguments").performClick()
            onNodeWithText("Arguments: {}").assertExists()
            onNodeWithText("> Result").performClick()
            onNodeWithText("Result: done").assertExists()
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    public fun planUsesTheCliInlineChecklistPresentation(): Unit =
        runDesktopComposeUiTest {
            setContent {
                MaterialTheme {
                    StableEventDesktopView(
                        event = StablePlanUpdate(
                            callId = "plan",
                            arguments = UpdatePlanArgs(
                                explanation = "Current plan",
                                plan = listOf(
                                    PlanItemArg(
                                        "Inspect the current renderer",
                                        StepStatus.Completed,
                                    ),
                                    PlanItemArg(
                                        "Implement the checklist",
                                        StepStatus.InProgress,
                                    ),
                                    PlanItemArg(
                                        "Verify the history view",
                                        StepStatus.Pending,
                                    ),
                                ),
                            ),
                        ),
                        shellSessions = EmptyAgentShellSessionRegistry,
                        uiState = AgentHistoryDesktopUiState(),
                        expansionPrefix = "plan-entry",
                    )
                }
            }

            onNodeWithText("• Updated Plan").assertExists()
            onNodeWithText("  └ Current plan").assertExists()
            onNodeWithText("    [x] Inspect the current renderer").assertExists()
            onNodeWithText("    [>] Implement the checklist").assertExists()
            onNodeWithText("    [ ] Verify the history view").assertExists()
            onNodeWithText("update_plan").assertDoesNotExist()
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    public fun halfPageScrollFocusesTopFullyVisibleCommittedEntry(): Unit =
        runDesktopComposeUiTest {
            val uiState = AgentHistoryDesktopUiState()
            val focusRequesters = mutableMapOf<StoredDesktopHistoryKey, FocusRequester>()
            lateinit var scope: CoroutineScope
            val keys = List(12) { index ->
                StoredDesktopHistoryKey(
                    agentId = "agent",
                    generation = 1,
                    storageIndex = index,
                    providerId = null,
                )
            }
            setContent {
                scope = rememberCoroutineScope()
                MaterialTheme {
                    LazyColumn(
                        modifier = Modifier.width(240.dp).height(128.dp),
                        state = uiState.listState,
                        reverseLayout = false,
                    ) {
                        items(keys, key = { it }) { key ->
                            val requester = remember(key) { FocusRequester() }
                            DisposableEffect(key, requester) {
                                focusRequesters[key] = requester
                                onDispose { focusRequesters.remove(key) }
                            }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(32.dp)
                                    .focusRequester(requester)
                                    .focusable()
                                    .testTag("page-${key.storageIndex}"),
                            )
                        }
                    }
                }
            }

            onNodeWithTag("page-11").assertIsNotFocused()
            var initialIndex = 0
            runOnIdle { initialIndex = uiState.listState.firstVisibleItemIndex }
            runOnIdle {
                scope.launch {
                    uiState.listState.pageHistory(
                        towardOlder = true,
                        uiState = uiState,
                        entryFocusRequesters = focusRequesters,
                    )
                }
            }
            waitForIdle()

            var target: StoredDesktopHistoryKey? = null
            runOnIdle {
                assertTrue(uiState.listState.firstVisibleItemIndex < initialIndex)
                assertFalse(uiState.followsLatest)
                target = uiState.listState.layoutInfo
                    .desktopHistoryPageFocusKey(towardTop = true)
            }
            val focused = assertNotNull(target)
            onNodeWithTag("page-${focused.storageIndex}").assertIsFocused()
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    public fun generationReplacementRebindsLazyEntryFocusRequester(): Unit =
        runDesktopComposeUiTest {
            val uiState = AgentHistoryDesktopUiState()
            val focusRequesters = mutableMapOf<StoredDesktopHistoryKey, FocusRequester>()
            val initialKey = StoredDesktopHistoryKey(
                agentId = "agent",
                generation = 0,
                storageIndex = 17,
                providerId = null,
            )
            val replacementKey = StoredDesktopHistoryKey(
                agentId = "agent",
                generation = 1,
                storageIndex = 4,
                providerId = null,
            )
            val currentItem = mutableStateOf(
                StoredDesktopHistoryItem(
                    key = initialKey,
                    entry = AgentHistoryEntry(
                        key = AgentHistoryEntryKey(initialKey.storageIndex),
                        event = StableCleanEvent.AssistantMessage(
                            listOf(ContentItem.OutputText("initial")),
                        ),
                    ),
                ),
            )
            setContent {
                val item = currentItem.value
                MaterialTheme {
                    LazyColumn(
                        modifier = Modifier.width(480.dp).height(180.dp),
                    ) {
                        item(key = item.key) {
                            CommittedHistoryDesktopItem(
                                item = item,
                                entryFocusRequesters = focusRequesters,
                                uiState = uiState,
                                shellSessions = EmptyAgentShellSessionRegistry,
                                canActOnHistory = false,
                                onRequestRevert = {},
                                onRequestFork = {},
                                modifier = Modifier.testTag("replaceable-history-entry"),
                            )
                        }
                    }
                }
            }

            runOnIdle {
                assertEquals(setOf(initialKey), focusRequesters.keys)
                focusRequesters.getValue(initialKey).requestFocus()
            }
            onNodeWithTag("replaceable-history-entry").assertIsFocused()

            runOnIdle {
                currentItem.value = StoredDesktopHistoryItem(
                    key = replacementKey,
                    entry = AgentHistoryEntry(
                        key = AgentHistoryEntryKey(replacementKey.storageIndex),
                        event = StableCleanEvent.AssistantMessage(
                            listOf(ContentItem.OutputText("replacement")),
                        ),
                    ),
                )
            }
            waitForIdle()

            runOnIdle {
                assertEquals(setOf(replacementKey), focusRequesters.keys)
                focusRequesters.getValue(replacementKey).requestFocus()
            }
            onNodeWithTag("replaceable-history-entry").assertIsFocused()
            onNodeWithText("replacement").assertExists()
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    public fun traversalFromComposerReachesNewestCommittedEntry(): Unit =
        runDesktopComposeUiTest {
            val uiState = AgentHistoryDesktopUiState()
            val entries = listOf(
                "older" to 1,
                "newest" to 2,
            )
            setContent {
                MaterialTheme {
                    Column(Modifier.width(480.dp)) {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                            reverseLayout = false,
                        ) {
                            items(entries, key = { it.second }) { (message, index) ->
                                CommittedHistoryDesktopRow(
                                    entry = AgentHistoryEntry(
                                        key = AgentHistoryEntryKey(index),
                                        event = StableCleanEvent.AssistantMessage(
                                            listOf(ContentItem.OutputText(message)),
                                        ),
                                    ),
                                    generation = 1,
                                    focusRequester = remember(index) { FocusRequester() },
                                    uiState = uiState,
                                    shellSessions = EmptyAgentShellSessionRegistry,
                                    canActOnHistory = false,
                                    onRequestRevert = {},
                                    onRequestFork = {},
                                    modifier = Modifier.testTag("history-$message"),
                                )
                            }
                        }
                        DesktopComposer(
                            text = "",
                            cursorOffset = 0,
                            submitKey = DesktopComposerSubmitKey.Enter,
                            onValueChange = { _, _ -> },
                            onSubmit = {},
                            autoFocus = true,
                        )
                    }
                }
            }

            onNode(hasSetTextAction()).assertIsFocused()
            onNode(hasSetTextAction()).performKeyInput {
                keyDown(Key.ShiftLeft)
                pressKey(Key.Tab)
                keyUp(Key.ShiftLeft)
            }
            onNodeWithTag("history-newest").assertIsFocused()
            onNodeWithTag("history-newest").performKeyInput {
                keyDown(Key.ShiftLeft)
                pressKey(Key.Tab)
                keyUp(Key.ShiftLeft)
            }
            onNodeWithTag("history-older").assertIsFocused()
            onNodeWithTag("history-older").performKeyInput { pressKey(Key.Tab) }
            onNodeWithTag("history-newest").assertIsFocused()
            onNodeWithTag("history-newest").performKeyInput { pressKey(Key.Tab) }
            onNode(hasSetTextAction()).assertIsFocused()
        }

    @Test
    public fun localStateTracksFollowIntentAndExpansionIndependently(): Unit {
        val state = AgentHistoryDesktopUiState()

        state.beginUserScroll()
        assertFalse(state.followsLatest)
        state.recordUserScroll(atLatest = true)
        assertTrue(state.followsLatest)

        state.setExpanded("first", true)
        assertTrue(state.isExpanded("first"))
        assertFalse(state.isExpanded("second"))
        state.setExpanded("first", false)
        assertFalse(state.isExpanded("first"))
    }
}

private object EmptyAgentShellSessionRegistry : AgentShellSessionRegistry {
    override val activeSessions =
        MutableStateFlow<Map<Int, AgentShellSession>>(emptyMap())
}
