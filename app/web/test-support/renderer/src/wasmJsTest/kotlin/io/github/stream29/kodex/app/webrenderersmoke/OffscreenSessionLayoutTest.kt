@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.window.ComposeViewport
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.agent.contract.AgentSettingsViewModel
import io.github.stream29.kodex.app.application.contract.*
import io.github.stream29.kodex.app.session.contract.*
import io.github.stream29.kodex.app.ui.WithBrowserComponentRenderers
import io.github.stream29.kodex.app.ui.compose.ComposeApplicationScreen
import io.github.stream29.kodex.cli.settings.*
import kotlinx.browser.document
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTextAreaElement
import kotlin.test.*

val offscreenSessionLayoutTest by testSuite {
    test("NewSessionDraft grows from one to six lines and shrinks in the actual browser page") { runtimeScene { _, runtime, owner ->
        val agent = pageAgent("soft-wrap-draft", runtime, owner)
        val draft = object : NewSessionViewModel, AgentSettingsViewModel by agent {
            override val composer = agent.composer
            override val runtimeConfiguration = runtime
            override suspend fun submit(expectedRevision: Long): PersistedSessionViewModel? = error("Unexpected submission")
            override fun close() = Unit
        }
        val app = object : NavigationApplication(emptyList()) {
            override val newSession = draft
            override val popup = MutableStateFlow<ApplicationPopupState>(ApplicationPopupState.Closed)
        }
        val preferences = object : SidebarSettingsViewModel {
            override val state = MutableStateFlow(SidebarSettings())
            override fun initializeViewport(columns: Int) = Unit
            override suspend fun selectLeft(content: SidebarContent) = Unit
            override suspend fun selectRight(content: SidebarContent) = Unit
            override suspend fun resizeLeft(columns: Int) = Unit
            override suspend fun resizeRight(columns: Int) = Unit
        }
        val host = document.createElement("div") as HTMLElement
        host.style.cssText = "position:fixed;left:0;top:0;width:640px;height:900px;opacity:0;pointer-events:none"
        document.body!!.appendChild(host)
        var mounted by mutableStateOf(true)
        val failures = mutableListOf<Throwable>()
        try {
            ComposeViewport(host) {
                WithBrowserComponentRenderers {
                    MaterialTheme {
                        if (mounted) ComposeApplicationScreen(app, MutableStateFlow(NewLineKey.ShiftEnter), preferences, failures::add, { false })
                    }
                }
            }
            suspend fun await(condition: () -> Boolean) {
                withTimeout(15_000) { while (!condition()) { assertTrue(failures.isEmpty(), failures.toString()); delay(20) } }
            }
            await { sessionLayoutInput(host) != null }
            val input = assertNotNull(sessionLayoutInput(host))
            for (lines in listOf(1, 2, 5, 6, 7, 10, 6, 3, 1)) {
                val text = List(lines) { "line" }.joinToString("\n")
                draft.composer.update(text, text.length)
                val height = minOf(lines, 6) * 24.0
                await { input.value == text && kotlin.math.abs(input.getBoundingClientRect().height - height) < 1.0 &&
                    input.style.getPropertyValue("overflow-y") == if (lines > 6) "auto" else "hidden" }
                assertTrue(input.scrollWidth <= input.clientWidth)
                assertEquals("soft", input.wrap)
            }
            val token = "https://example.test/" + "x".repeat(300)
            draft.composer.update(token, token.length)
            await { input.value == token && input.getBoundingClientRect().height > 24.0 }
            assertTrue(input.scrollWidth <= input.clientWidth)
            assertEquals(token, input.value)
            draft.composer.update("", 0)
            await { input.value.isEmpty() && input.getBoundingClientRect().height == 24.0 }
            assertEquals("hidden", input.style.getPropertyValue("overflow-y"))
            assertTrue(failures.isEmpty())
        } finally {
            mounted = false
            try { withTimeout(5_000) { while (sessionLayoutInput(host) != null) delay(20) } }
            finally { host.remove(); agent.closeFixture() }
        }
    } }
}

private fun sessionLayoutInput(host: HTMLElement): HTMLTextAreaElement? = js("""{
    const find = root => {
        const input = root.querySelector('textarea');
        if (input) return input;
        for (const node of root.querySelectorAll('*')) {
            if (node.shadowRoot) { const found = find(node.shadowRoot); if (found) return found; }
        }
        return null;
    };
    return find(host);
}""")
