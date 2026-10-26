package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.hooksettings.*
import io.github.stream29.kodex.app.ui.compose.rememberComposeHookDialogRenderer
import io.github.stream29.kodex.cli.settings.*
import io.github.stream29.kodex.rpc.models.NotificationHook
import io.github.stream29.kodex.rpc.models.NotificationHookType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

val offscreenHookSettingsTest by testSuite {
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
