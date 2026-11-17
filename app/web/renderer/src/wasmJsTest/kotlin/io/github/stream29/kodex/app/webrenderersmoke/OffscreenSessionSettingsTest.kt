package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.app.pathpicker.contract.*
import io.github.stream29.kodex.app.settings.createSessionSettingsViewModel
import io.github.stream29.kodex.app.settings.contract.*
import io.github.stream29.kodex.app.ui.*
import io.github.stream29.kodex.app.ui.compose.rememberComposeSettingsRenderer
import io.github.stream29.kodex.cli.sessionsettings.*
import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.io.files.Path
import kotlin.test.*

val offscreenSessionSettingsTest by testSuite {
    test("session panel keeps identity and canonical four field writes bound to displayed target") { sessionSettingsScene { source, child, _ ->
        val scene = OffscreenScene.create { SessionSettingsContent(child, rememberComposeSettingsRenderer()) }
        try {
            scene.settle { scene.hasDescription("Model: orphan") }
            assertTrue(scene.text("Identity").boundsInWindow.top < scene.text("Model behavior").boundsInWindow.top)
            assertTrue(scene.hasText("Target"))
            assertTrue(scene.hasText("/fixture"))
            scene.clickDescription("Model: orphan")
            scene.settle { scene.hasDescription("Model: catalog-b") }
            scene.clickDescription("Model: catalog-b")
            scene.settle { source.writes.size == 1 }
            scene.clickDescription("Reasoning: provider-effort")
            scene.settle { scene.hasDescription("Reasoning: max") }
            scene.clickDescription("Reasoning: max")
            scene.settle { source.writes.size == 2 }
            scene.clickDescription("Service tier: default")
            scene.settle { scene.hasDescription("Service tier: ultrafast") }
            scene.clickDescription("Service tier: ultrafast")
            scene.settle { source.writes.size == 3 }
            scene.clickDescription("Questions: ask user")
            scene.settle { scene.hasDescription("Questions: no question") }
            scene.clickDescription("Questions: no question")
            scene.settle { source.writes.size == 4 }
            val initial = source.snapshot.configuration
            assertEquals(listOf(
                0L to initial.copy(model = OpenAiModelId("catalog-b")),
                0L to initial.copy(reasoningEffort = ReasoningEffort.Max),
                0L to initial.copy(serviceTier = ServiceTier.Ultrafast),
                0L to initial.copy(requestUserInputMode = RequestUserInputMode.NoQuestion),
            ), source.writes)
            assertTrue(scene.hasDescription("Model: orphan"))
            assertEquals(0L, source.snapshot.revision)
            assertEquals(0, source.closes)
            scene.frame()
        } finally { scene.close() }
        assertIs<SessionSettingsState.Available>(child.state.value)
        assertEquals(0, source.closes)
    } }
    test("old session callback cannot retarget new revision and noneditable configuration still permits exact Rename") { sessionSettingsScene { source, child, _ ->
        val callbacks = mutableMapOf<String, (String) -> Unit>()
        val scene = OffscreenScene.create {
            val graphical = rememberComposeSettingsRenderer()
            val recording = object : SettingsRenderer by graphical {
                @Composable
                override fun Choice(
                    fieldKey: String, label: String, options: List<UiChoiceOption>, selectedKey: String,
                    onSelect: (String) -> Unit, enabled: Boolean, supportingText: String?,
                ) {
                    callbacks[fieldKey] = onSelect
                    graphical.Choice(fieldKey, label, options, selectedKey, onSelect, enabled, supportingText)
                }
            }
            SessionSettingsContent(child, recording)
        }
        try {
            scene.settle { scene.hasDescription("Model: orphan") }
            val stale = callbacks.getValue(SessionChoiceField.Model.name)
            source.publish(source.snapshot.copy(revision = 1, sessionName = "Replacement"))
            scene.settle { scene.hasText("Replacement") }
            stale("catalog-b")
            scene.settle { scene.hasText("Replacement") }
            assertTrue(source.writes.isEmpty())
            source.publish(source.snapshot.copy(revision = 2, editable = false))
            scene.settle { (child.state.value as? SessionSettingsState.Available)?.snapshot?.revision == 2L }
            scene.clickDescription("Model: orphan")
            assertFalse(scene.hasDescription("Model: catalog-b"))
            scene.click("Browse")
            assertNull(child.directoryPicker.value)
            scene.click("Rename")
            scene.settle { child.rename.value != null }
            assertEquals(2L, assertNotNull(child.rename.value).expectedRevision)
            assertTrue(source.writes.isEmpty())
        } finally { scene.close() }
        assertNull(child.rename.value)
        assertEquals(0, source.closes)
    } }
    test("exact children survive stale dismissal and unmount hides interactions without closing source") { sessionSettingsScene { source, child, browsers ->
        val visible = MutableStateFlow(true)
        var dismissDirectory: (() -> Unit)? = null
        val scene = OffscreenScene.create {
            if (visible.collectAsState().value) {
                SessionSettingsContent(child, rememberComposeSettingsRenderer())
                SessionSettingsChildren(
                    child,
                    rename = { _, dismiss -> UiAction("Cancel rename", onClick = dismiss) },
                    directory = { _, dismiss ->
                        dismissDirectory = dismiss
                        UiText("Fixture directory child")
                        UiAction("Cancel directory", onClick = dismiss)
                    },
                )
            } else UiText("unmounted")
        }
        try {
            scene.settle { scene.hasText("Browse") }
            scene.click("Browse")
            scene.settle { scene.hasText("Fixture directory child") }
            val old = assertNotNull(child.directoryPicker.value)
            val staleDismiss = assertNotNull(dismissDirectory)
            child.requestWorkingDirectory(0)
            scene.settle { child.directoryPicker.value !== old }
            val replacement = assertNotNull(child.directoryPicker.value)
            assertEquals(1, browsers.first().closes)
            staleDismiss()
            assertSame(replacement, child.directoryPicker.value)
            visible.value = false
            scene.settle { scene.hasText("unmounted") }
            assertNull(child.directoryPicker.value)
            assertEquals(listOf(1, 1), browsers.map { it.closes })
            assertEquals(0, source.closes)
            source.publish(source.snapshot.copy(revision = 1, sessionName = "Observed while hidden"))
            visible.value = true
            scene.settle { scene.hasText("Observed while hidden") }
            source.state.value = SessionSettingsDataState.Unavailable
            scene.settle { scene.hasText("No selected session") }
            assertFalse(scene.hasText("Rename"))
            assertFalse(scene.hasText("Model behavior"))
            assertTrue(source.writes.isEmpty())
        } finally { scene.close() }
    } }
}

private suspend fun sessionSettingsScene(
    block: suspend (SessionSettingsSource, SessionSettingsViewModel, List<SettingsDirectoryFixture>) -> Unit,
) = offscreenTest {
    val source = SessionSettingsSource()
    val browsers = mutableListOf<SettingsDirectoryFixture>()
    val owner = CoroutineScope(currentCoroutineContext() + Job(currentCoroutineContext()[Job]))
    val child = createSessionSettingsViewModel(
        SessionSettingsDependencies(
            source,
            MutableStateFlow(listOf(ModelInfo(OpenAiModelId("catalog-a"), "A"), ModelInfo(OpenAiModelId("catalog-b"), "B"))),
            createDirectoryPicker = { SettingsDirectoryFixture(it).also(browsers::add) },
            reportUnhandledError = { failure, _ -> throw AssertionError("Unexpected settings failure", failure) },
        ), owner,
    )
    try { block(source, child, browsers) } finally { child.close(); owner.coroutineContext.job.cancelAndJoin() }
}

internal class SessionSettingsSource : SessionSettingsDataSource {
    override val state = MutableStateFlow<SessionSettingsDataState>(SessionSettingsDataState.Available(
        SessionSettingsSnapshot(0, "Target", SessionSettingsConfiguration(
            OpenAiModelId("orphan"), Path("/fixture"), ReasoningEffort.Custom("provider-effort"),
            ServiceTier.Default, RequestUserInputMode.AskUser,
        ), editable = true),
    ))
    val snapshot get() = (state.value as SessionSettingsDataState.Available).snapshot
    val writes = mutableListOf<Pair<Long, SessionSettingsConfiguration>>()
    var closes = 0
    fun publish(snapshot: SessionSettingsSnapshot) { state.value = SessionSettingsDataState.Available(snapshot) }
    override suspend fun tryUpdateConfiguration(expectedRevision: Long, configuration: SessionSettingsConfiguration): Boolean {
        writes += expectedRevision to configuration
        return true
    }
    override suspend fun tryRenameSession(expectedRevision: Long, sessionName: String): Boolean =
        error("Rename should not be submitted in this scene")
    override fun close() { closes++ }
}

internal class SettingsDirectoryFixture(path: Path) : DirectoryPickerViewModel {
    override val state = MutableStateFlow(DirectoryPickerState(
        loadState = DirectoryPickerLoadState.Ready(1, path, path, emptyList(), null, emptyMap()),
    ))
    private val output = Channel<DirectoryPickerEffect>(Channel.BUFFERED)
    override val effects = output.receiveAsFlow()
    var closes = 0
    override fun navigateTo(directory: Path) = Unit
    override fun navigateUp() = Unit
    override fun retry() = Unit
    override fun updateFilter(query: String) = Unit
    override fun clearFilter() = Unit
    override fun confirm() = Unit
    override fun close() { closes++; output.close() }
}
