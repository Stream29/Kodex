package io.github.stream29.kodex.cli.settings

import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.rpc.models.NotificationHook
import io.github.stream29.kodex.rpc.models.NotificationHookType
import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val hookSettingsContentTest by testSuite {
    test("rendersNativeHookNamesAndTypesUnderTheManagementHeader") {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(96)) {
                    HookSettingsContent(
                        hooks = listOf(managedHook()),
                        onAdd = {},
                        onOpenDetails = {},
                    )
                }
            }

            assertTrue("Hooks [Add]" in snapshot, snapshot)
            assertTrue("notify Assistant message, Unhandled error" in snapshot, snapshot)
            assertFalse("Import from Codex" in snapshot, snapshot)
            assertFalse("Enabled" in snapshot, snapshot)
            assertFalse("matcher" in snapshot.lowercase(), snapshot)
            assertFalse("[Edit]" in snapshot, snapshot)
            assertFalse("[Delete]" in snapshot, snapshot)
        }
    }

    test("rendersHookDetailsAndActionsInsideDialog") {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Box {
                    TuiPopupHost(modifier = Modifier.width(96).height(24)) {
                        HookDetailsDialog(
                            hook = managedHook(),
                            onDismiss = {},
                            onEdit = {},
                            onDelete = {},
                        )
                    }
                }
            }

            assertTrue("notify" in snapshot, snapshot)
            assertTrue("Types: Assistant message, Unhandled error" in snapshot, snapshot)
            assertTrue("[Close] [Edit] [Delete]" in snapshot, snapshot)
            assertFalse("Command:" in snapshot, snapshot)
        }
    }

    test("editorContainsOnlyNameTypeAndCommand") {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Box {
                    TuiPopupHost(modifier = Modifier.width(96).height(24)) {
                        HookEditorDialog(
                            request = HookEditorRequest(
                                name = "guard tools",
                                draft = NotificationHook(
                                    name = "guard tools",
                                    types = setOf(NotificationHookType.StopAssistantMessage),
                                    command = "guard-command",
                                ),
                            ),
                            onDismiss = {},
                            onSave = {},
                        )
                    }
                }
            }

            assertTrue("Edit Hook" in snapshot, snapshot)
            assertTrue("Name" in snapshot, snapshot)
            assertTrue("[x] Assistant message" in snapshot, snapshot)
            assertTrue("[ ] Request user input" in snapshot, snapshot)
            assertTrue("[ ] Suggest subagent" in snapshot, snapshot)
            assertTrue("[ ] Unhandled error" in snapshot, snapshot)
            assertTrue("Command" in snapshot, snapshot)
            assertTrue("guard-command" in snapshot, snapshot)
            assertFalse("Matcher" in snapshot, snapshot)
            assertFalse("Timeout" in snapshot, snapshot)
            assertFalse("Environment" in snapshot, snapshot)
        }
    }

}

private fun managedHook(): NotificationHook =
    NotificationHook(
        name = "notify",
        types = linkedSetOf(NotificationHookType.StopAssistantMessage, NotificationHookType.StopUnhandledError),
        command = "notify-command",
    )
