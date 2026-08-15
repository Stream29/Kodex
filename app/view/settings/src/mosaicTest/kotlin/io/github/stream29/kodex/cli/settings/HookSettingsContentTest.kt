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
import io.github.stream29.kodex.hook.contract.HookImportDecision
import io.github.stream29.kodex.hook.contract.HookImportDisposition
import io.github.stream29.kodex.hook.contract.HookImportItem
import io.github.stream29.kodex.hook.contract.HookImportPreview
import io.github.stream29.kodex.hook.contract.HookImportSupport
import io.github.stream29.kodex.hook.contract.HookManagedSourceState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HookSettingsContentTest {
    @Test
    fun rendersCompactSourceButtonsWithoutFlattenedDetailsOrActions() = runTest {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Column(Modifier.width(96)) {
                    HookSettingsContent(
                        featureEnabled = true,
                        sources = listOf(managedSource()),
                        onSetFeatureEnabled = {},
                        onAdd = {},
                        onOpenDetails = {},
                        onImport = {},
                    )
                }
            }

            assertTrue("[Project checks · Enabled · 2 commands]" in snapshot, snapshot)
            assertTrue("[Disable all] [Add] [Import from Codex]" in snapshot, snapshot)
            assertFalse("Events:" in snapshot, snapshot)
            assertFalse("Environment:" in snapshot, snapshot)
            assertFalse("Imported from" in snapshot, snapshot)
            assertFalse("[Edit]" in snapshot, snapshot)
            assertFalse("[Delete]" in snapshot, snapshot)
            assertFalse("private-hook-token" in snapshot, snapshot)
        }
    }

    @Test
    fun rendersSanitizedSourceDetailsAndActionsInsideDialog() = runTest {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Box {
                    TuiPopupHost(modifier = Modifier.width(96).height(24)) {
                        HookSourceDetailsDialog(
                            source = managedSource(),
                            onDismiss = {},
                            onEdit = {},
                            onDelete = {},
                            onSetEnabled = {},
                        )
                    }
                }
            }

            assertTrue("State: Enabled" in snapshot, snapshot)
            assertTrue("Commands: 2 commands" in snapshot, snapshot)
            assertTrue("Events: Pre tool, Stop" in snapshot, snapshot)
            assertTrue("Environment: HOOK_TOKEN (values hidden)" in snapshot, snapshot)
            assertTrue(
                "Imported from: project: /workspace/.codex/hooks.json" in snapshot,
                snapshot,
            )
            assertTrue("[Disable] [Edit] [Delete] [Close]" in snapshot, snapshot)
            assertFalse("private-hook-token" in snapshot, snapshot)
        }
    }

    @Test
    fun importDefaultsSelectEverySupportedSourceAndReplaceConflicts() {
        assertEquals(
            mapOf(
                "user:/home/user/.codex/hooks.json" to HookImportDecision.Import,
                "project:/workspace/.codex/hooks.json" to HookImportDecision.Replace,
                "user:/home/user/.codex/config.toml" to HookImportDecision.Skip,
            ),
            importPreview().defaultImportDecisions(),
        )
    }

    @Test
    fun importDialogShowsLoadedSelectionWithoutPreviewStep() = runTest {
        runMosaicTest {
            val snapshot = setContentAndSnapshot {
                Box {
                    TuiPopupHost(modifier = Modifier.width(100).height(30)) {
                        HookImportDialog(
                            preview = importPreview(),
                            onApply = { _, _ -> },
                            onDismiss = {},
                        )
                    }
                }
            }

            assertTrue("All supported sources are selected." in snapshot, snapshot)
            assertTrue("[✓ User checks · New]" in snapshot, snapshot)
            assertTrue("[✓ Project checks · Replace existing · partial]" in snapshot, snapshot)
            assertTrue("[– Unsupported source · Unsupported]" in snapshot, snapshot)
            assertTrue("[Import selected (2)]" in snapshot, snapshot)
            assertTrue("Environment: HOOK_TOKEN (values hidden)" in snapshot, snapshot)
            assertTrue("Prompt handlers were excluded." in snapshot, snapshot)
            assertFalse("[Preview]" in snapshot, snapshot)
            assertFalse("private-hook-token" in snapshot, snapshot)
            assertFalse("private-command" in snapshot, snapshot)
        }
    }

    private fun managedSource(): HookManagedSourceState =
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
        )

    private fun importPreview(): HookImportPreview =
        HookImportPreview(
            id = 1,
            filter = "",
            items = listOf(
                HookImportItem(
                    sourceKey = "user:/home/user/.codex/hooks.json",
                    displayName = "User checks",
                    sourceKind = HookCodexSourceKind.User,
                    normalizedPath = "/home/user/.codex/hooks.json",
                    disposition = HookImportDisposition.New,
                    support = HookImportSupport.Full,
                    configuredEvents = listOf(HookEvent.PreToolUse),
                    commandCount = 1,
                    environmentNames = emptyList(),
                    selectable = true,
                ),
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
                    sourceKey = "user:/home/user/.codex/config.toml",
                    displayName = "Unsupported source",
                    sourceKind = HookCodexSourceKind.User,
                    normalizedPath = "/home/user/.codex/config.toml",
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
        )
}
