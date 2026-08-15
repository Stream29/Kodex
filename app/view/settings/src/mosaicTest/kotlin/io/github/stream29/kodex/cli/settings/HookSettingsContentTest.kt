package io.github.stream29.kodex.cli.settings

import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Column
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.hook.contract.HookCodexImportSummary
import io.github.stream29.kodex.hook.contract.HookCodexSourceKind
import io.github.stream29.kodex.hook.contract.HookEvent
import io.github.stream29.kodex.hook.contract.HookImportDisposition
import io.github.stream29.kodex.hook.contract.HookImportItem
import io.github.stream29.kodex.hook.contract.HookImportPreview
import io.github.stream29.kodex.hook.contract.HookImportSupport
import io.github.stream29.kodex.hook.contract.HookManagedSourceState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HookSettingsContentTest {
    @Test
    fun rendersOnlySanitizedManagedSourceState() = runTest {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(96)) {
                    HookSettingsContent(
                        featureEnabled = true,
                        sources = listOf(
                            HookManagedSourceState(
                                sourceId = "source-id",
                                name = "Project checks",
                                enabled = true,
                                importedFrom = HookCodexImportSummary(
                                    sourceKind = HookCodexSourceKind.Project,
                                    normalizedPath = "/workspace/.codex/hooks.json",
                                ),
                                configuredEvents = listOf(
                                    HookEvent.PreToolUse,
                                    HookEvent.Stop,
                                ),
                                commandCount = 2,
                                environmentNames = listOf("HOOK_TOKEN"),
                            ),
                        ),
                        onSetFeatureEnabled = {},
                        onAdd = {},
                        onEdit = {},
                        onDelete = {},
                        onSetEnabled = { _, _ -> },
                        onImport = {},
                    )
                }
            }

            assertTrue("Project checks · Enabled · 2 commands" in snapshot, snapshot)
            assertTrue("Events: Pre tool, Stop" in snapshot, snapshot)
            assertTrue("Environment: HOOK_TOKEN (values hidden)" in snapshot, snapshot)
            assertTrue(
                "Imported from project: /workspace/.codex/hooks.json" in snapshot,
                snapshot,
            )
            assertTrue("[Import from Codex]" in snapshot, snapshot)
            assertFalse("private-hook-token" in snapshot, snapshot)
        }
    }

    @Test
    fun rendersFilteredImportClassificationWithoutHookContents() = runTest {
        runMosaicTest {
            setContentAndSnapshot {
                Box {
                    TuiPopupHost(modifier = Modifier.width(100).height(30)) {
                        HookImportDialog(
                            preview = HookImportPreview(
                                id = 1,
                                filter = "project",
                                items = listOf(
                                    HookImportItem(
                                        sourceKey = "project:/workspace/.codex/hooks.json",
                                        displayName = "Project checks",
                                        sourceKind = HookCodexSourceKind.Project,
                                        normalizedPath = "/workspace/.codex/hooks.json",
                                        disposition = HookImportDisposition.Conflict,
                                        support = HookImportSupport.Partial,
                                        configuredEvents = listOf(HookEvent.Stop),
                                        commandCount = 1,
                                        environmentNames = listOf("HOOK_TOKEN"),
                                        excludedDetails = listOf(
                                            "Prompt handlers were excluded.",
                                        ),
                                        selectable = true,
                                    ),
                                    HookImportItem(
                                        sourceKey = "user:/home/user/.codex/hooks.json",
                                        displayName = "Unsupported source",
                                        sourceKind = HookCodexSourceKind.User,
                                        normalizedPath = "/home/user/.codex/hooks.json",
                                        disposition = null,
                                        support = HookImportSupport.Unsupported,
                                        configuredEvents = emptyList(),
                                        commandCount = 0,
                                        environmentNames = emptyList(),
                                        excludedDetails = listOf(
                                            "The source contains no supported command Hooks.",
                                        ),
                                        selectable = false,
                                    ),
                                ),
                            ),
                            onPreview = {},
                            onApply = { _, _ -> },
                            onDismiss = {},
                        )
                    }
                }
            }

            val snapshot = awaitSnapshot()
            assertTrue("Import Hooks from Codex" in snapshot, snapshot)
            assertTrue("Project checks: Conflict · partial" in snapshot, snapshot)
            assertTrue("Environment: HOOK_TOKEN (values hidden)" in snapshot, snapshot)
            assertTrue("Prompt handlers were excluded." in snapshot, snapshot)
            assertTrue("Unsupported source: Unsupported" in snapshot, snapshot)
            assertFalse("private-hook-token" in snapshot, snapshot)
            assertFalse("private-command" in snapshot, snapshot)
        }
    }
}
