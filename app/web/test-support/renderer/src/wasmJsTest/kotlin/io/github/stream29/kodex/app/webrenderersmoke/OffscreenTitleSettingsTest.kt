package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.sessiontitlesettings.*
import io.github.stream29.kodex.app.ui.compose.rememberComposeSettingsRenderer
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.cli.settings.SessionTitleSettingsContent
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

val offscreenTitleSettingsTest by testSuite {
    test("controlled title toggle waits for source and disabled menus do not reject programmatic commands") {
        titleScene { ports, child, scene ->
            scene.click("Automatic session title")
            scene.settle { ports.enabledWrites.size == 1 }
            assertEquals(listOf(true to false), ports.enabledWrites)
            assertTrue(ports.settings.value.enabled)
            assertEquals(ToggleableState.On, scene.nodes().mapNotNull {
                it.config.getOrNull(SemanticsProperties.ToggleableState)
            }.single())
            ports.settings.value = ports.settings.value.copy(enabled = false)
            scene.settle { scene.hasText("Available when automatic session titles are enabled.") }
            assertEquals(ToggleableState.Off, scene.nodes().mapNotNull {
                it.config.getOrNull(SemanticsProperties.ToggleableState)
            }.single())
            scene.clickDescription("Title model: compiled-title")
            assertFalse(scene.hasDescription("Title model: first"))
            assertTrue(ports.modelWrites.isEmpty())
            child.setModel(OpenAiModelId("programmatic"))
            assertEquals<List<Pair<OpenAiModelId?, OpenAiModelId?>>>(listOf(null to OpenAiModelId("programmatic")), ports.modelWrites)
            assertFalse(ports.settings.value.enabled)
        }
    }
    test("title menus retain injected default and custom reasoning without optimistic publication or hidden writes") {
        titleScene { ports, _, scene ->
            assertTrue(ports.enabledWrites.isEmpty() && ports.modelWrites.isEmpty() && ports.reasoningWrites.isEmpty())
            scene.clickDescription("Title model: compiled-title")
            scene.settle { scene.hasDescription("Title model: first") }
            scene.clickDescription("Title model: first")
            scene.settle { ports.modelWrites.size == 1 }
            assertEquals<List<Pair<OpenAiModelId?, OpenAiModelId?>>>(listOf(null to OpenAiModelId("first")), ports.modelWrites)
            assertNull(ports.settings.value.model)
            assertTrue(scene.hasDescription("Title model: compiled-title"))
            scene.clickDescription("Title reasoning: provider-effort")
            scene.settle { scene.hasDescription("Title reasoning: max") }
            for (effort in listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")) {
                assertTrue(scene.hasDescription("Title reasoning: $effort"))
            }
            scene.clickDescription("Title reasoning: max")
            scene.settle { ports.reasoningWrites.size == 1 }
            assertEquals<List<Pair<ReasoningEffort, ReasoningEffort>>>(
                listOf(ReasoningEffort.Custom("provider-effort") to ReasoningEffort.Max), ports.reasoningWrites,
            )
            assertIs<ReasoningEffort.Custom>(ports.settings.value.reasoningEffort)
            ports.operationFailure.value = true
            scene.settle { scene.hasText("A settings operation failed.") }
            scene.click("Dismiss")
            scene.settle { !scene.hasText("A settings operation failed.") }
            assertEquals(1, ports.dismissals)
            assertEquals(1, ports.modelWrites.size)
            assertEquals(1, ports.reasoningWrites.size)
            scene.frame()
        }
    }
}

private suspend fun titleScene(block: suspend (TitlePorts, SessionTitleSettingsViewModel, OffscreenScene) -> Unit) = offscreenTest {
    val ports = TitlePorts()
    val owner = CoroutineScope(currentCoroutineContext() + Job(currentCoroutineContext()[Job]))
    val child = createSessionTitleSettingsViewModel(ports, owner)
    var output: OffscreenScene? = null
    try {
        val scene = OffscreenScene.create {
            SessionTitleSettingsContent(child, rememberComposeSettingsRenderer())
        }.also { output = it }
        scene.settle { scene.hasDescription("Title model: compiled-title") }
        block(ports, child, scene)
        scene.close()
        output = null
        assertFalse(child.state.value.closed)
    } finally {
        output?.close()
        child.close()
        owner.coroutineContext.job.cancelAndJoin()
    }
}

internal class TitlePorts : SessionTitleSettingsDependencies {
    override val settings = MutableStateFlow(SessionTitleSettings(reasoningEffort = ReasoningEffort.Custom("provider-effort")))
    override val defaultModel = OpenAiModelId("compiled-title")
    override val models = MutableStateFlow(listOf(ModelInfo(OpenAiModelId("first"), "First")))
    override val operationFailure = MutableStateFlow(false)
    val enabledWrites = mutableListOf<Pair<Boolean, Boolean>>()
    val modelWrites = mutableListOf<Pair<OpenAiModelId?, OpenAiModelId?>>()
    val reasoningWrites = mutableListOf<Pair<ReasoningEffort, ReasoningEffort>>()
    var dismissals = 0
    override fun setEnabled(expected: Boolean, enabled: Boolean): SessionTitleWriteAdmission {
        enabledWrites += expected to enabled
        return SessionTitleWriteAdmission.Accepted
    }
    override fun setModel(expected: OpenAiModelId?, model: OpenAiModelId?): SessionTitleWriteAdmission {
        modelWrites += expected to model
        return SessionTitleWriteAdmission.Accepted
    }
    override fun setReasoningEffort(expected: ReasoningEffort, reasoningEffort: ReasoningEffort): SessionTitleWriteAdmission {
        reasoningWrites += expected to reasoningEffort
        return SessionTitleWriteAdmission.Accepted
    }
    override fun reportFailure(failure: Throwable) { operationFailure.value = true }
    override fun dismissFailure() { dismissals++; operationFailure.value = false }
}
