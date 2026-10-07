package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.settings.contract.SettingsPage
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.app.hooksettings.HookEditorDraft
import io.github.stream29.kodex.app.hooksettings.HookSettingsDialog
import io.github.stream29.kodex.app.test.testAnswer
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.rpc.models.*
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem as Fs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.io.files.Path
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

val unhandledErrorReportingTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("frontend unhandled errors use the one Hook consumer without inventing an Agent Stop") {
        applicationFixture { app, home ->
            val session = assertNotNull(app.viewModel.materializeNewSession(
                assertIs<NewSessionViewModel>(app.viewModel.navigation.value.selected),
            ))
            val popup = app.viewModel.openSettingsPopup(session, SettingsPage.Hooks)
            val hooks = popup.viewModel.global.hookSettings
            val configured = listOf(
                NotificationHook("failed-local-hook", setOf(NotificationHookType.StopUnhandledError), "exit 17"),
                NotificationHook("capture", NotificationHookType.entries.toSet(),
                    "cat >> host-errors.jsonl; printf '\\n' >> host-errors.jsonl"),
            )
            for (hook in configured) {
                hooks.add()
                val editor = assertIs<HookSettingsDialog.Editing>(hooks.state.value.dialog)
                hooks.updateDraft(editor.token) { HookEditorDraft(hook.name, hook.command, hook.types) }
                hooks.save(editor.token)
                hooks.state.first { state -> state.hooks.any { it.name == hook.name } }
            }
            app.viewModel.dismissPopup(popup)
            val agent = assertNotNull(session.rootAgent.value)
            val output = Path(home, "host-errors.jsonl")
            suspend fun lines(count: Int): List<String> = withTimeout(15.seconds) {
                while (true) {
                    val values = if (Fs.exists(output)) Fs.readString(output).lineSequence()
                        .filter(String::isNotBlank).toList() else emptyList()
                    if (values.size >= count) return@withTimeout values
                    delay(10)
                }
                @Suppress("UNREACHABLE_CODE") emptyList()
            }
            // A real backend Stop primes the actual shared subscriber, not a test-only sink.
            agent.submit(listOf(ContentItem.InputText("before local errors")))
            assertEquals(1, lines(1).size)
            agent.running.first { !it }
            app.reportUnhandledError(CancellationException("do not notify cancellation"))
            repeat(2) { app.reportUnhandledError(IOException("literal $(touch unwanted) \"failure\"")) }
            val observed = lines(3)
            assertEquals(3, observed.size)
            for (line in observed.drop(1)) {
                val payload = Json.parseToJsonElement(line).jsonObject
                assertEquals(setOf("type", "message"), payload.keys)
                assertEquals("unhandled_error", payload.getValue("type").jsonPrimitive.content)
                assertEquals("literal $(touch unwanted) \"failure\"", payload.getValue("message").jsonPrimitive.content)
            }
            assertFalse(Fs.exists(Path(home, "unwanted")))
            assertSame(session, app.viewModel.navigation.value.selected)
            agent.submit(listOf(ContentItem.InputText("after local errors")))
            val resumed = lines(4)
            assertEquals(4, resumed.size, "Local errors and failed Hooks must not kill or recursively notify the host.")
            assertTrue(resumed.last().contains("stop_assistant_message"))
        }
    }

    test("one application subscriber dispatches Stop JSON to a local multi-type Hook") {
        var fail = false
        applicationFixture(response = { if (fail) error("test provider failure") else testAnswer() }) { app, home ->
            val session = assertNotNull(app.viewModel.materializeNewSession(assertIs<NewSessionViewModel>(app.viewModel.navigation.value.selected)))
            val popup = app.viewModel.openSettingsPopup(session, SettingsPage.Hooks)
            val hook = NotificationHook("capture", NotificationHookType.entries.toSet(),
                "cat >> notification.jsonl; printf '\\n' >> notification.jsonl")
            val hooks = popup.viewModel.global.hookSettings
            hooks.add()
            val editor = hooks.state.value.dialog as HookSettingsDialog.Editing
            hooks.updateDraft(editor.token) { HookEditorDraft(hook.name, hook.command, hook.types) }
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
