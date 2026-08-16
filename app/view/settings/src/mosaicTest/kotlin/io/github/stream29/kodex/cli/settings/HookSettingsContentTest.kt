package io.github.stream29.kodex.cli.settings

import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.hook.contract.HookDraft
import io.github.stream29.kodex.hook.contract.HookManagedState
import io.github.stream29.kodex.hook.contract.HookType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HookSettingsContentTest {
    @Test
    fun rendersNativeHookNamesAndTypesUnderTheManagementHeader() = runTest {
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
            assertTrue("[guard tools · Pre tool use]" in snapshot, snapshot)
            assertFalse("Import from Codex" in snapshot, snapshot)
            assertFalse("Enabled" in snapshot, snapshot)
            assertFalse("matcher" in snapshot.lowercase(), snapshot)
            assertFalse("[Edit]" in snapshot, snapshot)
            assertFalse("[Delete]" in snapshot, snapshot)
        }
    }

    @Test
    fun rendersHookDetailsAndActionsInsideDialog() = runTest {
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

            assertTrue("guard tools" in snapshot, snapshot)
            assertTrue("Type: Pre tool use" in snapshot, snapshot)
            assertTrue("[Close] [Edit] [Delete]" in snapshot, snapshot)
            assertFalse("Command:" in snapshot, snapshot)
        }
    }

    @Test
    fun editorContainsOnlyNameTypeAndCommand() = runTest {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Box {
                    TuiPopupHost(modifier = Modifier.width(96).height(24)) {
                        HookEditorDialog(
                            request = HookEditorRequest(
                                name = "guard tools",
                                draft = HookDraft(
                                    name = "guard tools",
                                    type = HookType.PreToolUse,
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
            assertTrue("Type: [Pre tool use]" in snapshot, snapshot)
            assertTrue("Command" in snapshot, snapshot)
            assertTrue("guard-command" in snapshot, snapshot)
            assertFalse("Matcher" in snapshot, snapshot)
            assertFalse("Timeout" in snapshot, snapshot)
            assertFalse("Environment" in snapshot, snapshot)
        }
    }

    private fun managedHook(): HookManagedState =
        HookManagedState(
            name = "guard tools",
            type = HookType.PreToolUse,
        )
}
