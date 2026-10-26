package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.Composable
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.settings.createNewSessionDefaultsViewModel
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.app.ui.SettingsRenderer
import io.github.stream29.kodex.app.ui.UiChoiceOption
import io.github.stream29.kodex.app.ui.compose.rememberComposeSettingsRenderer
import io.github.stream29.kodex.cli.newsessiondefaults.DefaultsChoiceField
import io.github.stream29.kodex.cli.newsessiondefaults.NewSessionDefaultsContent
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

val offscreenDefaultsTest by testSuite {
    test("four defaults menus preserve canonical choices and typed baselines without optimistic state") { defaultsScene { ports, child ->
        val scene = OffscreenScene.create { NewSessionDefaultsContent(child, rememberComposeSettingsRenderer()) }
        try {
            scene.settle { scene.hasDescription("Model: orphan") }
            val labels = listOf("Model", "Reasoning", "Service tier", "Questions")
            assertEquals(labels, labels.sortedBy { scene.text(it).boundsInWindow.top })
            scene.clickDescription("Model: orphan")
            scene.settle { scene.hasDescription("Model: catalog-b") }
            assertTrue(scene.hasDescription("Model: catalog-a"))
            scene.clickDescription("Model: catalog-b")
            scene.settle { ports.admissions.size == 1 }
            scene.clickDescription("Reasoning: provider-effort")
            scene.settle { scene.hasDescription("Reasoning: max") }
            for (effort in listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")) {
                assertTrue(scene.hasDescription("Reasoning: $effort"))
            }
            scene.clickDescription("Reasoning: max")
            scene.settle { ports.admissions.size == 2 }
            scene.clickDescription("Service tier: default")
            scene.settle { scene.hasDescription("Service tier: ultrafast") }
            for (tier in listOf("default", "fast", "flex", "ultrafast")) assertTrue(scene.hasDescription("Service tier: $tier"))
            scene.clickDescription("Service tier: ultrafast")
            scene.settle { ports.admissions.size == 3 }
            scene.clickDescription("Questions: ask user")
            scene.settle { scene.hasDescription("Questions: no question") }
            scene.clickDescription("Questions: no question")
            scene.settle { ports.admissions.size == 4 }
            assertEquals(listOf(
                Triple("model", "orphan", "catalog-b"),
                Triple("effort", "provider-effort", "max"),
                Triple("tier", "Default", "Ultrafast"),
                Triple("questions", "AskUser", "NoQuestion"),
            ), ports.admissions)
            assertEquals(0L, child.state.value.revision)
            assertEquals("orphan", ports.defaults.value.model.value)
            assertTrue(scene.hasDescription("Model: orphan"))
            assertFalse(scene.hasText("Working directory"))
            scene.frame()
        } finally { scene.close() }
        assertTrue(child.state.value.active)
    } }
    test("captured defaults callback submits displayed revision and stale selection does not become latest write") { defaultsScene { ports, child ->
        val selections = mutableMapOf<String, (String) -> Unit>()
        val scene = OffscreenScene.create {
            val graphical = rememberComposeSettingsRenderer()
            val recording = object : SettingsRenderer by graphical {
                @Composable
                override fun Choice(
                    fieldKey: String, label: String, options: List<UiChoiceOption>, selectedKey: String,
                    onSelect: (String) -> Unit, enabled: Boolean, supportingText: String?,
                ) {
                    selections[fieldKey] = onSelect
                    graphical.Choice(fieldKey, label, options, selectedKey, onSelect, enabled, supportingText)
                }
            }
            NewSessionDefaultsContent(child, recording)
        }
        try {
            scene.settle { scene.hasDescription("Model: orphan") }
            val stale = assertNotNull(selections[DefaultsChoiceField.Model.name])
            ports.defaults.value = ports.defaults.value.copy(model = OpenAiModelId("updated"))
            scene.settle { scene.hasDescription("Model: updated") }
            assertEquals(1L, child.state.value.revision)
            stale("catalog-b")
            assertTrue(ports.admissions.isEmpty())
            selections.getValue(DefaultsChoiceField.Model.name)("catalog-b")
            assertEquals(listOf(Triple("model", "updated", "catalog-b")), ports.admissions)
            ports.operationFailure.value = true
            scene.settle { scene.hasText("A settings operation failed.") }
            scene.click("Dismiss")
            scene.settle { !scene.hasText("A settings operation failed.") }
            assertEquals(1, ports.dismissals)
        } finally { scene.close() }
    } }
    test("closed defaults keep passive display but all controls and failure acknowledgement are disabled") { defaultsScene { ports, child ->
        ports.operationFailure.value = true
        val scene = OffscreenScene.create { NewSessionDefaultsContent(child, rememberComposeSettingsRenderer()) }
        try {
            scene.settle { scene.hasDescription("Model: orphan") }
            child.close()
            scene.settle { !child.state.value.active }
            scene.clickDescription("Model: orphan")
            assertFalse(scene.hasDescription("Model: catalog-b"))
            scene.click("Dismiss")
            assertTrue(ports.admissions.isEmpty())
            assertEquals(0, ports.dismissals)
            assertTrue(scene.hasText("Model behavior"))
        } finally { scene.close() }
    } }
}

private suspend fun defaultsScene(block: suspend (DefaultsPorts, NewSessionSettingsViewModel) -> Unit) = offscreenTest {
    val ports = DefaultsPorts()
    val owner = CoroutineScope(currentCoroutineContext() + Job(currentCoroutineContext()[Job]))
    val child = createNewSessionDefaultsViewModel(ports, owner)
    try { block(ports, child) } finally { child.close(); owner.coroutineContext.job.cancelAndJoin() }
}

internal class DefaultsPorts : NewSessionDefaultsDependencies {
    override val defaults = MutableStateFlow(KodexNewSessionSettings(
        OpenAiModelId("orphan"), ReasoningEffort.Custom("provider-effort"),
    ))
    override val models = MutableStateFlow(listOf(
        ModelInfo(OpenAiModelId("catalog-a"), "A"), ModelInfo(OpenAiModelId("catalog-b"), "B"),
    ))
    override val operationFailure = MutableStateFlow(false)
    val admissions = mutableListOf<Triple<String, String, String>>()
    var dismissals = 0
    override fun admitModel(expected: OpenAiModelId, requested: OpenAiModelId): NewSessionDefaultsAdmission =
        admit("model", expected.value, requested.value)
    override fun admitReasoningEffort(expected: ReasoningEffort, requested: ReasoningEffort): NewSessionDefaultsAdmission =
        admit("effort", expected.wireName, requested.wireName)
    override fun admitServiceTier(expected: ServiceTier, requested: ServiceTier): NewSessionDefaultsAdmission =
        admit("tier", expected.name, requested.name)
    override fun admitRequestUserInputMode(expected: RequestUserInputMode, requested: RequestUserInputMode): NewSessionDefaultsAdmission =
        admit("questions", expected.name, requested.name)
    private fun admit(field: String, expected: String, requested: String): NewSessionDefaultsAdmission {
        admissions += Triple(field, expected, requested)
        return NewSessionDefaultsAdmission.Accepted
    }
    override fun reportFailure(failure: Throwable) { throw AssertionError("Unexpected command failure", failure) }
    override fun dismissFailure() { dismissals++; operationFailure.value = false }
}
