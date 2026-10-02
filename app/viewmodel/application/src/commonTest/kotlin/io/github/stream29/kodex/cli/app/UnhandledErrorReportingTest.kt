package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.settings.contract.SettingsPage
import io.github.stream29.kodex.app.hooksettings.HookEditorDraft
import io.github.stream29.kodex.app.hooksettings.HookSettingsDialog
import io.github.stream29.kodex.app.test.testAnswer
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.rpc.models.*
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem as Fs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.io.files.Path
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

val unhandledErrorReportingTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("one application subscriber dispatches Stop JSON to a local multi-type Hook") {
        var fail = false
        applicationFixture(response = { if (fail) error("test provider failure") else testAnswer() }) { app, home ->
            val session = app.viewModel.materializeNewSession(0)
            val popup = app.viewModel.openSettingsPopup(session, SettingsPage.Hooks)
            val hook = NotificationHook("capture", NotificationHookType.entries.toSet(),
                "cat >> notification.jsonl; printf '\\n' >> notification.jsonl")
            val hooks = popup.viewModel.global.hookSettings
            hooks.add()
            val editor = hooks.state.value.dialog as HookSettingsDialog.Editing
            hooks.updateDraft(editor.token, HookEditorDraft(hook.name, hook.command, hook.types))
            hooks.save(editor.token)
            hooks.state.first { it.hooks == listOf(hook) }
            app.viewModel.dismissPopup(popup) // The single application consumer outlives the editor.
            val agent = requireNotNull(session.rootAgent.value)
            agent.submit(listOf(ContentItem.InputText("first")))
            val output = Path(home, "notification.jsonl")
            suspend fun lines(count: Int): List<String> = withTimeout(15.seconds) {
                while (true) {
                    val lines = if (Fs.exists(output)) Fs.readString(output).lineSequence().filter { it.isNotBlank() }.toList() else emptyList()
                    if (lines.size >= count) return@withTimeout lines
                    delay(10)
                }
                @Suppress("UNREACHABLE_CODE") emptyList()
            }
            val first = lines(1)
            assertEquals(1, first.size)
            assertTrue(first.single().contains("assistant"))
            agent.running.first { !it }
            fail = true
            agent.submit(listOf(ContentItem.InputText("second")))
            val both = lines(2)
            assertEquals(2, both.size)
            assertTrue(both.last().contains("unhandled"))
            assertTrue(both.last().contains("test provider failure"))
        }
    }
}
