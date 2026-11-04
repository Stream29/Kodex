package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.hooksettings.*
import io.github.stream29.kodex.app.ui.compose.rememberComposeHookDialogRenderer
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.app.ui.UiTextRole
import io.github.stream29.kodex.cli.settings.*
import io.github.stream29.kodex.rpc.models.NotificationHook
import io.github.stream29.kodex.rpc.models.NotificationHookType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

val offscreenHookSettingsTest by testSuite {
    test("hook delete initially focuses Cancel and Escape only dismisses confirmation") { hookScene { ports, child ->
        var dismissals = 0
        var deletes = 0
        val visible = mutableStateOf(true)
        val scene = OffscreenScene.create {
            if (visible.value) rememberComposeHookDialogRenderer().Deleting(ports.hooks.value.single(), null,
                { dismissals++; visible.value = false }, { deletes++ })
        }
        try {
            scene.settle { scene.overlayTextFocused("Cancel") }
            scene.key(Key.Tab)
            scene.settle { scene.overlayTextFocused("Delete") }
            scene.key(Key.Tab, shift = true)
            scene.settle { scene.overlayTextFocused("Cancel") }
            scene.escape()
            scene.settle { dismissals == 1 && !scene.hasText("Cancel") }
            assertEquals(0, deletes)
        } finally { scene.close() }
    } }

    test("Hook editor frame is strictly shorter than the original with all supported choices") { hookScene { _, child ->
        child.add()
        val editor = assertIs<HookSettingsDialog.Editing>(child.state.value.dialog)
        val compact = OffscreenScene.create(width = 1024, height = 900) {
            HookSettingsDialogHost(child, rememberComposeHookDialogRenderer())
        }
        val compactHeight = try {
            compact.settle { compact.hasText("Name") }
            compact.hookPaneHeight("Add Hook")
        } finally { compact.close() }
        val baseline = OffscreenScene.create(width = 1024, height = 900) {
            HookEditorContent(editor, NotificationHookType.entries.toSet(), {}, {}, {}) { title, dismiss, body ->
                Dialog(dismiss) {
                    Surface(
                        Modifier.semantics { paneTitle = "Original Hook frame" },
                        shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Column(
                            Modifier.widthIn(max = 720.dp).heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) { UiText(title, UiTextRole.Title); body() }
                    }
                }
            }
        }
        try {
            baseline.settle { baseline.hasText("Name") }
            assertTrue(compactHeight < baseline.hookPaneHeight("Original Hook frame"))
        } finally { baseline.close() }
        assertSame(editor.token, assertIs<HookSettingsDialog.Editing>(child.state.value.dialog).token)
    } }

    for ((width, height) in listOf(1024 to 768, 360 to 640, 320 to 240)) {
        for (fontScale in listOf(1f, 1.5f)) {
            test("Hook form keeps exact rejected draft and scrollable actions ${width}x$height font=$fontScale") { hookScene { ports, child ->
                child.add()
                val token = assertIs<HookSettingsDialog.Editing>(child.state.value.dialog).token
                val rejection = "Synthetic Hook queue rejection with a complete explanation. ".repeat(12)
                ports.admission = HookWriteAdmission.Rejected(rejection)
                val scene = OffscreenScene.create(width = width, height = height) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        HookSettingsDialogHost(child, rememberComposeHookDialogRenderer())
                    }
                }
                try {
                    scene.settle { scene.hasText("Add Hook") && scene.hasText("Name") }
                    assertTrue(scene.nodes().any {
                        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Name") == true &&
                            it.config.getOrNull(SemanticsProperties.Focused) == true
                    }, "The original editor focus remains on Name.")
                    scene.editHookField(0, "synthetic-hook")
                    scene.editHookField(1, "echo fixture-only")
                    scene.showHookText("Save")
                    scene.click("Save")
                    scene.settle { scene.hasText(rejection) }
                    val rejected = assertIs<HookSettingsDialog.Editing>(child.state.value.dialog)
                    assertSame(token, rejected.token)
                    assertEquals("synthetic-hook", rejected.draft.name)
                    assertEquals("echo fixture-only", rejected.draft.command)
                    assertTrue(ports.saved.isEmpty())
                    scene.showHookText("Cancel")
                    assertTrue(scene.text("Cancel").boundsInWindow.bottom <= height)
                    scene.click("Cancel")
                    scene.settle { child.state.value.dialog == HookSettingsDialog.Hidden }
                    assertEquals(1, ports.releases)
                    val longName = "完整 Hook target ".repeat(25)
                    ports.hooks.value = listOf(NotificationHook(longName, NotificationHookType.entries.toSet(), "private-command"))
                    scene.settle { child.state.value.hooks.single().name == longName }
                    child.details(longName)
                    scene.settle { scene.hasText(longName) }
                    assertTrue(scene.hookPaneHeight(longName) <= height)
                    assertFalse(scene.hasText("private-command"), "Details keeps the original command-disclosure boundary.")
                    scene.showHookText("Close")
                    assertTrue(scene.text("Close").boundsInWindow.bottom <= height)
                    scene.click("Close")
                    scene.settle { child.state.value.dialog == HookSettingsDialog.Hidden }
                    scene.frame()
                } finally { scene.close() }
            } }
        }
    }

    test("shared Hook details hide commands and same-frame edits preserve latest draft fields") { hookScene { ports, child ->
        val scene = OffscreenScene.create {
            HookSettingsPage(child)
            HookSettingsDialogHost(child, rememberComposeHookDialogRenderer())
        }
        try {
            scene.settle { scene.hasText("notify Assistant message") }
            assertFalse(scene.hasText("notify-command"))
            scene.click("notify Assistant message")
            scene.settle { scene.hasText("Edit") }
            assertFalse(scene.hasText("notify-command"))
            scene.click("Edit")
            scene.settle { scene.hasText("Edit Hook") }
            scene.editHookField(0, " New name ")
            scene.editHookField(1, "echo value")
            scene.click("Request user input")
            scene.click("Assistant message")
            scene.click("Save")
            scene.settle { child.state.value.dialog == HookSettingsDialog.Hidden }
            assertEquals(listOf(NotificationHook(
                "New name", setOf(NotificationHookType.StopRequestUserInput), "echo value",
            )), ports.saved)
            assertEquals("notify", ports.hooks.value.single().name)
            assertEquals(1, ports.releases)
            scene.frame()
        } finally { scene.close() }
        assertFalse(child.state.value.closed)
    } }
    test("Hook replacement rejects captured callbacks and rejection retains current supported-type form") {
        hookScene(setOf(NotificationHookType.StopAssistantMessage)) { ports, child ->
            child.add()
            var dismiss: (() -> Unit)? = null
            var save: (() -> Unit)? = null
            var draft: (((HookEditorDraft) -> HookEditorDraft) -> Unit)? = null
            val scene = OffscreenScene.create {
                val graphical = rememberComposeHookDialogRenderer()
                val capturing = object : HookDialogRenderer by graphical {
                    @Composable
                    override fun Editor(
                        editor: HookSettingsDialog.Editing, supportedTypes: Set<NotificationHookType>,
                        onDraftChange: ((HookEditorDraft) -> HookEditorDraft) -> Unit, onDismiss: () -> Unit, onSave: () -> Unit,
                    ) {
                        dismiss = onDismiss; save = onSave; draft = onDraftChange
                        graphical.Editor(editor, supportedTypes, onDraftChange, onDismiss, onSave)
                    }
                }
                HookSettingsDialogHost(child, capturing)
            }
            try {
                scene.settle { scene.hasText("Add Hook") }
                assertFalse(scene.hasText("Request user input"))
                val staleDismiss = assertNotNull(dismiss)
                val staleSave = assertNotNull(save)
                val staleDraft = assertNotNull(draft)
                child.add()
                val expected = (child.state.value.dialog as HookSettingsDialog.Editing).token
                scene.settle { scene.hasText("Add Hook") }
                staleDismiss(); staleSave(); staleDraft { error("Stale update must not run") }
                assertSame(expected, (child.state.value.dialog as HookSettingsDialog.Editing).token)
                assertTrue(ports.saved.isEmpty())
                ports.admission = HookWriteAdmission.Rejected("Queue unavailable.")
                scene.editHookField(0, "new-hook")
                scene.editHookField(1, "echo queued")
                scene.click("Save")
                scene.settle { scene.hasText("Queue unavailable.") }
                val rejected = child.state.value.dialog as HookSettingsDialog.Editing
                assertSame(expected, rejected.token)
                assertEquals("new-hook", rejected.draft.name)
                assertEquals("echo queued", rejected.draft.command)
                assertTrue(ports.saved.isEmpty())
                scene.click("Cancel")
                scene.settle { child.state.value.dialog == HookSettingsDialog.Hidden }
                assertEquals(2, ports.releases)
            } finally { scene.close() }
        }
    }
    test("Hook confirmation deletes the frozen original once and shared failure is acknowledged without execution") { hookScene { ports, child ->
        val original = ports.hooks.value.single()
        child.details(original.name)
        child.requestDelete((child.state.value.dialog as HookSettingsDialog.Details).token)
        val scene = OffscreenScene.create {
            HookSettingsPage(child)
            HookSettingsDialogHost(child, rememberComposeHookDialogRenderer())
        }
        try {
            scene.settle { scene.hasText("Delete 'notify'?") }
            ports.hooks.value = listOf(original.copy(command = "changed-command"))
            scene.settle { child.state.value.hooks.single().command == "changed-command" }
            assertEquals(original, (child.state.value.dialog as HookSettingsDialog.Deleting).original)
            scene.click("Delete")
            scene.settle { ports.deleted.size == 1 && child.state.value.dialog == HookSettingsDialog.Hidden }
            assertEquals(listOf(original), ports.deleted)
            assertEquals("changed-command", ports.hooks.value.single().command)
            ports.operationFailure.value = true
            scene.settle { scene.hasText("A settings operation failed.") }
            scene.click("Dismiss")
            scene.settle { !scene.hasText("A settings operation failed.") }
            assertEquals(1, ports.dismissals)
            child.close()
            scene.settle { !scene.hasText("Hooks") }
            assertFalse(scene.hasText("Add"))
            assertTrue(ports.saved.isEmpty())
        } finally { scene.close() }
    } }
}

private fun OffscreenScene.editHookField(index: Int, text: String) {
    val edits = nodes().mapNotNull { it.config.getOrNull(SemanticsActions.SetText)?.action }
    assertEquals(2, edits.size)
    assertTrue(edits[index](AnnotatedString(text)))
}

private fun OffscreenScene.hookPaneHeight(title: String): Float = nodes().single {
    it.config.getOrNull(SemanticsProperties.PaneTitle) == title
}.boundsInRoot.height

private suspend fun OffscreenScene.showHookText(label: String) {
    fun visible() = hasText(label) && text(label).boundsInWindow.height > 0 && text(label).boundsInWindow.width > 0
    if (visible()) return
    for (scroll in nodes().mapNotNull { it.config.getOrNull(SemanticsActions.ScrollBy)?.action }) {
        for (delta in listOf(-10_000f, 10_000f)) {
            scroll(0f, delta)
            settle { true }
            if (visible()) return
        }
    }
    error("Hook dialog control is not visible: $label")
}

private suspend fun hookScene(
    supportedTypes: Set<NotificationHookType> = NotificationHookType.entries.toSet(),
    block: suspend (HookPorts, HookSettingsViewModel) -> Unit,
) = offscreenTest {
    val ports = HookPorts(supportedTypes)
    val owner = CoroutineScope(currentCoroutineContext() + Job(currentCoroutineContext()[Job]))
    val child = createHookSettingsViewModel(ports, owner)
    try { block(ports, child) } finally { child.close(); owner.coroutineContext.job.cancelAndJoin() }
}

internal class HookPorts(override val supportedTypes: Set<NotificationHookType>) : HookSettingsDependencies {
    override val hooks = MutableStateFlow(listOf(NotificationHook(
        "notify", setOf(NotificationHookType.StopAssistantMessage), "notify-command",
    )))
    override val operationFailure = MutableStateFlow(false)
    var admission: HookWriteAdmission = HookWriteAdmission.Accepted
    val saved = mutableListOf<NotificationHook>()
    val deleted = mutableListOf<NotificationHook>()
    var releases = 0
    var dismissals = 0
    override fun captureEditor(name: String?): HookEditHandle = object : HookEditHandle {
        override val original = hooks.value.firstOrNull { it.name == name }
        private var released = false
        override fun save(updated: NotificationHook): HookWriteAdmission {
            if (admission == HookWriteAdmission.Accepted) saved += updated
            return admission
        }
        override fun release() { if (!released) { released = true; releases++ } }
    }
    override fun delete(original: NotificationHook): HookWriteAdmission { deleted += original; return admission }
    override fun reportFailure(failure: Throwable) { operationFailure.value = true }
    override fun dismissFailure() { dismissals++; operationFailure.value = false }
}
