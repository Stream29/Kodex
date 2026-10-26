package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentcontext.contract.AgentContextCustomSource
import io.github.stream29.kodex.agentcontext.contract.AgentContextSourceSettings
import io.github.stream29.kodex.app.contextsourcesettings.*
import io.github.stream29.kodex.app.settings.contract.BuiltInContextSource
import io.github.stream29.kodex.app.ui.UiText
import io.github.stream29.kodex.app.ui.compose.rememberComposeSettingsRenderer
import io.github.stream29.kodex.cli.settings.ContextSourceSettingsContent
import io.github.stream29.kodex.cli.settings.ContextSourceSettingsDialogHost
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

val offscreenContextSourcesTest by testSuite {
    test("context list preserves built-in order controlled values and exact custom path commands") { contextScene { ports, child ->
        val scene = OffscreenScene.create { ContextSourceSettingsContent(child, rememberComposeSettingsRenderer()) }
        try {
            scene.settle { scene.hasText("None configured") }
            val labels = listOf("Agents home", "Kodex home", "Codex home", "Git root", "Working directory")
            assertEquals(labels, labels.sortedBy { scene.text(it).boundsInWindow.top })
            labels.forEach { scene.click(it) }
            assertEquals(BuiltInContextSource.entries.toList(), ports.builtIns.map { it.first })
            assertTrue(ports.builtIns.all { it.second && !it.third })
            assertTrue(ports.sources.value.agentsHomeEnabled)
            ports.sources.value = ports.sources.value.copy(customSources = listOf(AgentContextCustomSource("/custom")))
            scene.settle { scene.hasDescription("/custom: Enabled") }
            scene.clickDescription("/custom: Enabled")
            scene.click("Remove")
            assertEquals(listOf(AgentContextCustomSource("/custom") to false), ports.custom)
            assertEquals(listOf(AgentContextCustomSource("/custom")), ports.removed)
            assertEquals(listOf(AgentContextCustomSource("/custom")), ports.sources.value.customSources)
            scene.click("Add source")
            assertIs<ContextSourceSettingsDialog.Adding>(child.state.value.dialog)
            child.hidePage()
            ports.operationFailure.value = true
            scene.settle { scene.hasText("A settings operation failed.") }
            scene.click("Dismiss")
            scene.settle { !scene.hasText("A settings operation failed.") }
            assertEquals(1, ports.dismissals)
            scene.frame()
        } finally { scene.close() }
        assertFalse(child.state.value.closed)
    } }
    test("graphical context editor updates VM synchronously before Add and keeps exact token on errors") { contextScene { ports, child ->
        child.add()
        var dismiss: (() -> Unit)? = null
        val scene = OffscreenScene.create {
            ContextSourceSettingsDialogHost(child) { title, close, content ->
                dismiss = close
                UiText(title)
                content()
            }
        }
        try {
            scene.settle { scene.hasText("Add context source") }
            val oldDismiss = assertNotNull(dismiss)
            scene.editContextPath("relative")
            scene.click("Add")
            scene.settle { (child.state.value.dialog as? ContextSourceSettingsDialog.Adding)?.error != null }
            assertTrue(ports.saved.isEmpty())
            scene.editContextPath(" /new/source ")
            scene.click("Add")
            scene.settle { !scene.hasText("Add context source") }
            assertEquals(listOf(listOf(AgentContextCustomSource("/new/source"))), ports.saved)
            assertTrue(ports.sources.value.customSources.isEmpty())
            child.add()
            scene.settle { scene.hasText("Add context source") }
            val next = (child.state.value.dialog as ContextSourceSettingsDialog.Adding).token
            oldDismiss()
            assertSame(next, (child.state.value.dialog as ContextSourceSettingsDialog.Adding).token)
            child.updateDraft(next, "/from-vm")
            scene.settle { scene.contextPath() == "/from-vm" }
            ports.admission = ContextSourceWriteAdmission.Rejected("Queue unavailable.")
            scene.click("Add")
            scene.settle { scene.hasText("Queue unavailable.") }
            assertSame(next, (child.state.value.dialog as ContextSourceSettingsDialog.Adding).token)
            assertEquals("/from-vm", scene.contextPath())
            assertEquals(1, ports.saved.size)
            scene.click("Cancel")
            scene.settle { !scene.hasText("Add context source") }
        } finally { scene.close() }
    } }
    test("context hide and close remove only unaccepted dialog and render no closed controls") { contextScene { ports, child ->
        ports.operationFailure.value = true
        val scene = OffscreenScene.create { ContextSourceSettingsContent(child, rememberComposeSettingsRenderer(), showOperationFailure = false) }
        try {
            scene.settle { scene.hasText("Built-in sources") }
            assertFalse(scene.hasText("A settings operation failed."))
            child.add()
            child.hidePage()
            assertEquals(ContextSourceSettingsDialog.Hidden, child.state.value.dialog)
            assertTrue(ports.operationFailure.value)
            child.close()
            scene.settle { !scene.hasText("Built-in sources") }
            assertFalse(scene.hasText("Add source"))
            assertTrue(ports.saved.isEmpty())
        } finally { scene.close() }
    } }
}

private fun OffscreenScene.editContextPath(value: String) {
    val edit = nodes().mapNotNull { it.config.getOrNull(SemanticsActions.SetText)?.action }.single()
    assertTrue(edit(AnnotatedString(value)))
}

private fun OffscreenScene.contextPath(): String? =
    nodes().mapNotNull { it.config.getOrNull(SemanticsProperties.EditableText)?.text }.singleOrNull()

private suspend fun contextScene(block: suspend (ContextPorts, ContextSourceSettingsViewModel) -> Unit) = offscreenTest {
    val ports = ContextPorts()
    val owner = CoroutineScope(currentCoroutineContext() + Job(currentCoroutineContext()[Job]))
    val child = createContextSourceSettingsViewModel(ports, owner)
    try { block(ports, child) } finally { child.close(); owner.coroutineContext.job.cancelAndJoin() }
}

internal class ContextPorts : ContextSourceSettingsDependencies {
    override val sources = MutableStateFlow(AgentContextSourceSettings())
    override val operationFailure = MutableStateFlow(false)
    override val pathPolicy = object : ContextSourcePathPolicy {
        override val builtInNormalizedPaths = setOf("/fixture-home/.agents")
        override fun normalize(path: String): String? = when {
            path.isBlank() || '$' in path -> null
            path == "~" -> "/fixture-home"
            path.startsWith("~/") -> "/fixture-home/" + path.substring(2)
            path.startsWith("/") -> path
            else -> null
        }
    }
    var admission: ContextSourceWriteAdmission = ContextSourceWriteAdmission.Accepted
    val builtIns = mutableListOf<Triple<BuiltInContextSource, Boolean, Boolean>>()
    val custom = mutableListOf<Pair<AgentContextCustomSource, Boolean>>()
    val removed = mutableListOf<AgentContextCustomSource>()
    val saved = mutableListOf<List<AgentContextCustomSource>>()
    var dismissals = 0
    override fun setBuiltInEnabled(source: BuiltInContextSource, expected: Boolean, enabled: Boolean): ContextSourceWriteAdmission {
        builtIns += Triple(source, expected, enabled)
        return admission
    }
    override fun replaceCustomSources(expected: List<AgentContextCustomSource>, updated: List<AgentContextCustomSource>): ContextSourceWriteAdmission {
        if (admission == ContextSourceWriteAdmission.Accepted) saved += updated.toList()
        return admission
    }
    override fun setCustomEnabled(original: AgentContextCustomSource, enabled: Boolean): ContextSourceWriteAdmission {
        custom += original to enabled
        return admission
    }
    override fun removeCustom(original: AgentContextCustomSource): ContextSourceWriteAdmission { removed += original; return admission }
    override fun reportFailure(failure: Throwable) { operationFailure.value = true }
    override fun dismissFailure() { dismissals++; operationFailure.value = false }
}
