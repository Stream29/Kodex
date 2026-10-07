package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.focus.FocusRequester
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.ui.unit.IntOffset
import com.jakewharton.mosaic.testing.TestMosaic
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Text
import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.app.agent.contract.*
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.test.deleteTestDirectory
import io.github.stream29.kodex.app.test.testAnswer
import io.github.stream29.kodex.app.test.testSettings
import io.github.stream29.kodex.cli.agent.SuggestSubagentTaskDropdowns
import io.github.stream29.kodex.cli.agent.canEditHistory
import io.github.stream29.kodex.cli.components.TuiPopupHost
import io.github.stream29.kodex.cli.components.TuiPopupAnchor
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import io.github.stream29.kodex.cli.rpc.*
import io.github.stream29.kodex.cli.runtimeconfiguration.RuntimeConfigurationDropdowns
import io.github.stream29.kodex.cli.session.DefaultPersistedSessionViewModelRegistry
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.client.update
import io.github.stream29.kodex.rpc.inmemory.withInMemoryRpc
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.rpc.server.defaultBackendSettings
import io.github.stream29.kodex.rpc.server.withBackendServices
import io.github.stream29.kodex.utils.rpcexception.SessionNotActive
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// Real IO, subscriptions, keyboard handling and drawing must not race frontend state mutation.
private val composerHostDispatcher = Dispatchers.Default.limitedParallelism(1)

val composerHostRepairTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("real host Enter reports append failure once and retains exact draft and cursor") {
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        var appends = 0
        var resumes = 0
        composerHost(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    when (call.callableName) {
                        "appendUserMessage" -> {
                            appends++
                            entered.complete(Unit)
                            proceed.await()
                            error("injected append failure")
                        }
                        "resume" -> resumes++
                    }
                    return delegate.call(call)
                }
            }
        }) {
            val agent = requireNotNull(session.rootAgent.value)
            val binding = view.current()
            val revision = agent.composer.update("  keep this draft  ", 5)
            runComposerHostMosaicTest {
                val focus = FocusRequester()
                setContentAndSnapshot { RepairHost(agent, focus) }
                assertTrue(focus.requestFocus())
                sendKeyEvent(KeyboardEvent(13))
                awaitSnapshot()
                entered.await()
                assertContains(snapshotContaining("Submitting…"), "Submitting…")
                proceed.complete(Unit)
                agent.notification.first { it != null }
                val snapshot = snapshotContaining("injected append failure")
                assertEquals(1, Regex("injected append failure").findAll(snapshot).count(), snapshot)
                assertContains(snapshot, "Session operation failed.")
                assertFalse("Unable to submit:" in snapshot, snapshot)
                assertEquals("  keep this draft  ", agent.composer.state.value.text)
                assertEquals(5, agent.composer.state.value.cursorOffset)
                // Existing failure transition advances revision; it does not edit the failed draft.
                assertEquals(revision + 1, agent.composer.state.value.revision)
                assertIs<ComposerSubmissionState.Failed>(agent.composer.state.value.submission)
                assertEquals(AgentNotificationLevel.Error, agent.notification.value?.level)
                assertEquals(1L, agent.notification.value?.id)
                assertEquals(1, appends)
                assertEquals(0, resumes)
                assertEquals(SessionViewStatus.Ready, view.status.value)
                assertSame(binding, view.current())
                assertSame(agent, session.rootAgent.value)
                assertEquals(AgentStateValue.Empty, services.runtime.getState(view.index))
            }
        }
    }

    test("real host Enter ordinary resume failure uses original Agent policy and keeps Session Ready") {
        var appends = 0
        var resumes = 0
        composerHost(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "appendUserMessage") appends++
                    if (call.callableName == "resume") { resumes++; error("injected resume failure") }
                    return delegate.call(call)
                }
            }
        }) {
            val agent = requireNotNull(session.rootAgent.value)
            val binding = view.current()
            val revision = agent.composer.update("persist once")
            runComposerHostMosaicTest {
                val focus = FocusRequester()
                setContentAndSnapshot { RepairHost(agent, focus) }
                assertTrue(focus.requestFocus())
                sendKeyEvent(KeyboardEvent(13))
                awaitSnapshot()
                val notification = agent.notification.filterNotNull().first()
                assertEquals("Session operation failed.", notification.message)
                assertEquals("injected resume failure", notification.detail)
                val snapshot = snapshotContaining("injected resume failure")
                assertEquals(1, Regex("injected resume failure").findAll(snapshot).count(), snapshot)
                assertFalse("Unable to submit:" in snapshot, snapshot)
                assertEquals("", agent.composer.state.value.text)
                assertEquals(revision + 1, agent.composer.state.value.revision)
                assertIs<ComposerSubmissionState.Editing>(agent.composer.state.value.submission)
                assertEquals(AgentStateValue.UserMessage, services.runtime.getState(view.index))
                assertEquals(1, appends)
                assertEquals(1, resumes)
                assertEquals(1L, notification.id)
                assertEquals(SessionViewStatus.Ready, view.status.value)
                assertSame(binding, view.current())
                assertSame(agent, view.agent.value)
                assertSame(agent, session.rootAgent.value)
                assertIs<AgentLifecycleState.Open>(agent.lifecycle.value)
                // The binding is still usable, not merely a transient Ready snapshot.
                agent.renameThread("usable after failure")
                agent.settings.first { it.threadName == "usable after failure" }
                agent.dismissNotification(notification.id)
                agent.notification.first { it == null }
                val dismissed = snapshotMatching { "injected resume failure" !in it }
                assertFalse("injected resume failure" in dismissed, dismissed)
                assertEquals(1, appends) // No retry or compensating append.
            }
        }
    }

    test("real running host restores original steer hint and Enter never duplicates pending preview") {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        composerHost(response = {
            entered.complete(Unit)
            finish.await()
            testAnswer()
        }) {
            val agent = requireNotNull(session.rootAgent.value)
            val binding = view.current()
            binding.appendUserMessage(listOf(ContentItem.InputText("start")))
            agent.resume()
            entered.await()
            agent.composer.state.first { it.running }
            binding.pendingSteer.update { listOf(StableUserMessage(listOf(ContentItem.InputText("earlier")))) }
            agent.pendingSteer.first { it.size == 1 }
            agent.composer.update("next steer")
            try {
                runComposerHostMosaicTest {
                    val focus = FocusRequester()
                    val before = setContentAndSnapshot { RepairHost(agent, focus) }
                    assertContains(before, "Submit to steer") // Exact original UI hint.
                    assertEquals(1, Regex.escape("Pending steer (1)").toRegex().findAll(before).count(), before)
                    assertTrue(focus.requestFocus())
                    sendKeyEvent(KeyboardEvent(13))
                    awaitSnapshot()
                    agent.composer.state.first { it.text.isEmpty() }
                    agent.pendingSteer.first { it.size == 2 }
                    val after = snapshotContaining("Pending steer (2)")
                    assertEquals(1, Regex.escape("Pending steer (2)").toRegex().findAll(after).count(), after)
                    assertFalse("Submit to steer" in after, after)
                    assertNull(agent.notification.value)
                }
            } finally { finish.complete(Unit) }
        }
    }

    test("real host Enter inactive resume replaces binding without replaying accepted append") {
        var appends = 0
        var resumes = 0
        composerHost(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "appendUserMessage") appends++
                    if (call.callableName == "resume" && ++resumes == 1) throw SessionNotActive()
                    return delegate.call(call)
                }
            }
        }) {
            val agent = requireNotNull(session.rootAgent.value)
            val oldBinding = view.current()
            agent.composer.update("accepted once")
            runComposerHostMosaicTest {
                val focus = FocusRequester()
                setContentAndSnapshot { RepairHost(agent, focus) }
                assertTrue(focus.requestFocus())
                sendKeyEvent(KeyboardEvent(13))
                awaitSnapshot()
                val recovered = view.binding.filterNotNull().first { it !== oldBinding }
                view.status.first { it == SessionViewStatus.Ready }
                session.rootAgent.filterNotNull().first { it !== agent }
                assertEquals(1, appends)
                assertEquals(1, resumes)
                assertEquals(AgentStateValue.UserMessage, recovered.state.value)
                assertEquals(ComposerLifecycle.Closed, agent.composer.state.value.lifecycle)
                assertFailsWith<CancellationException> { oldBinding.resume() }
                assertEquals(ComposerSubmissionResult.Unavailable, agent.composer.submit(agent.composer.state.value.revision))
            }
        }
    }

    test("real host Enter cancelled resume is not reported as ordinary failure") {
        val cancelled = CompletableDeferred<Unit>()
        composerHost(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "resume") {
                        cancelled.complete(Unit)
                        throw CancellationException("injected command cancellation")
                    }
                    return delegate.call(call)
                }
            }
        }) {
            val agent = requireNotNull(session.rootAgent.value)
            val binding = view.current()
            agent.composer.update("accepted before cancellation")
            runComposerHostMosaicTest {
                val focus = FocusRequester()
                setContentAndSnapshot { RepairHost(agent, focus) }
                assertTrue(focus.requestFocus())
                sendKeyEvent(KeyboardEvent(13))
                awaitSnapshot()
                cancelled.await()
                val snapshot = awaitSnapshot()
                assertFalse("Session operation failed." in snapshot, snapshot)
                assertNull(agent.notification.value)
                assertSame(agent, view.agent.value)
                assertEquals("", agent.composer.state.value.text)
                assertEquals(SessionViewStatus.Ready, view.status.value)
                assertSame(binding, view.current())
                assertEquals(AgentStateValue.UserMessage, services.runtime.getState(view.index))
            }
        }
    }

    test("unmounting real host cancels renderer waiting only and accepted resume still completes") {
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        var resumes = 0
        var appends = 0
        composerHost(decorate = { delegate ->
            object : RpcClient by delegate {
                override suspend fun <T> call(call: RpcCall): T {
                    if (call.callableName == "appendUserMessage") appends++
                    if (call.callableName == "resume") {
                        resumes++
                        entered.complete(Unit)
                        proceed.await()
                    }
                    return delegate.call(call)
                }
            }
        }) {
            val agent = requireNotNull(session.rootAgent.value)
            agent.composer.update("accepted work")
            var visible by mutableStateOf(true)
            runComposerHostMosaicTest {
                val focus = FocusRequester()
                setContentAndSnapshot {
                    if (visible) RepairHost(agent, focus) else Text("renderer removed")
                }
                assertTrue(focus.requestFocus())
                sendKeyEvent(KeyboardEvent(13))
                awaitSnapshot()
                entered.await()
                assertEquals("", agent.composer.state.value.text)
                visible = false
                snapshotContaining("renderer removed")
                proceed.complete(Unit)
                agent.state.first { it == AgentStateValue.AssistantMessage }
                assertEquals(1, appends)
                assertEquals(1, resumes)
                assertSame(agent, session.rootAgent.value)
                assertEquals(SessionViewStatus.Ready, view.status.value)
                assertNull(agent.notification.value)
                assertEquals(ComposerLifecycle.Open, agent.composer.state.value.lifecycle)
            }
        }
    }

    test("Agent screen forwards the displayed history generation index and actual child") {
        composerHost {
            val agent = requireNotNull(session.rootAgent.value)
            val text = "exact displayed history target"
            val index = view.current().appendUserMessage(listOf(ContentItem.InputText(text)))
            agent.history.historyItems.first { it.size > 0 && agent.history.contains(it.generation, index) }
            agent.state.first { it.canEditHistory(agent.running.value) }
            val callbacks = mutableListOf<Triple<Long, Int, HistoryItemViewModel>>()
            runComposerHostMosaicTest {
                val focus = FocusRequester()
                setContentAndSnapshot {
                    RepairHost(agent, focus) { generation, storageIndex, item, _, _ ->
                        callbacks += Triple(generation, storageIndex, item)
                    }
                }
                val snapshot = snapshotContaining(text)
                val row = snapshot.lines().indexOfFirst { it.startsWith("User") }
                assertTrue(row >= 0, snapshot)
                val column = 1
                val window = agent.history.historyItems.value
                sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Press, MouseEvent.Button.Right))
                sendMouseEvent(MouseEvent(column, row, MouseEvent.Type.Release))
                // Mosaic drains input on a frame, not on delay. Pump its frame clock even
                // though this captured callback does not require a new rendered snapshot.
                try { awaitSnapshot(100.milliseconds) } catch (_: TimeoutCancellationException) { }
                assertTrue(callbacks.isNotEmpty(),
                    "No history action at ($column,$row), state=${agent.state.value}, " +
                        "running=${agent.running.value}:\n$snapshot")
                assertEquals(1, callbacks.size)
                val (generation, storageIndex, item) = callbacks.single()
                assertEquals(window.generation, generation)
                assertEquals(index, storageIndex)
                assertTrue((0 until window.size).any { window.peek(it) === item })
                assertTrue(agent.history.contains(generation, storageIndex))
            }
        }
    }
}

@Composable
private fun RepairHost(
    agent: AgentViewModel,
    focus: FocusRequester,
    onOpenHistoryEntryContextMenu: (Long, Int, HistoryItemViewModel, TuiPopupAnchor, IntOffset?) -> Unit =
        { _, _, _, _, _ -> },
) {
    TuiPopupHost(Modifier.width(100).height(24)) {
        AgentRuntimeScreen(
            viewModel = agent,
            columns = 100,
            rows = 24,
            newLineKey = NewLineKey.ShiftEnter,
            dropdowns = RuntimeConfigurationDropdowns.remember(agent),
            suggestionDropdowns = SuggestSubagentTaskDropdowns.remember(agent, null),
            onOpenHistoryEntryContextMenu = onOpenHistoryEntryContextMenu,
            onBrowseWorkingDirectory = {},
            onBrowseSuggestedWorkingDirectory = {},
            onOpenSettings = {},
            composerFocusRequester = focus,
        )
    }
}

private suspend fun runComposerHostMosaicTest(block: suspend TestMosaic<String>.() -> Unit) = runMosaicTest {
    try { block() } finally { cancel() }
}

private suspend fun TestMosaic<String>.snapshotContaining(text: String): String =
    snapshotMatching { text in it }

private suspend fun TestMosaic<String>.snapshotMatching(predicate: (String) -> Boolean): String =
    withTimeout(5.seconds) {
        var snapshot = draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
        while (!predicate(snapshot)) {
            snapshot = try {
                awaitSnapshot()
            } catch (_: TimeoutCancellationException) {
                draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
            }
            if (!predicate(snapshot)) delay(10)
        }
        snapshot
    }

private class ComposerHostFixture(
    val services: RpcServices,
    val session: PersistedSessionViewModel,
    val view: RpcSessionView,
)

/**
 * Focused host fixture: real backend, transport, binding and persisted root Agent.
 * Only RPC command failures and external model responses are injected. No isolated Composer VM
 * or replacement frontend state model stands in for the production assembly.
 */
private suspend fun composerHost(
    decorate: (RpcClient) -> RpcClient = { it },
    response: suspend FlowCollector<ResponsesStreamEvent>.() -> Unit = { testAnswer() },
    block: suspend ComposerHostFixture.() -> Unit,
) = withContext(composerHostDispatcher) { withTimeout(40.seconds) {
    val root = Path(SystemTemporaryDirectory, "kodex-composer-host-${Random.nextLong()}")
    val client = mockOpenAiClient {
        listModels { OpenAiResult.Success(ModelsResponse(listOf(ModelInfo(
            OpenAiModelId("test-model"), "Test Model", contextWindow = 100_000, maxContextWindow = 100_000,
        )))) }
        createResponse { flow { response() } }
    }
    try {
        withBackendServices(
            root, Path(root, "codex"), Path(root, "agents"),
            defaults = defaultBackendSettings().copy(sessionTitle = SessionTitleSettings(false)),
            createClient = { client },
            createLoginClient = { ComposerHostLoginClient },
        ) { backend ->
            withInMemoryRpc(backend::register) { raw ->
                val services = RpcServices(decorate(RestoringRpcClient(raw)))
                val models = MutableStateFlow(services.global.getModels())
                val views = RpcSessionViews(this, services, models)
                val sessions = DefaultPersistedSessionViewModelRegistry(views, models, this)
                try {
                    val index = services.global.createSession(testSettings(cwd = root))
                    val session = sessions.open(index)
                    ComposerHostFixture(services, session, views.open(index)).block()
                } finally { withContext(NonCancellable) { sessions.shutdown() } }
            }
        }
    } finally { withContext(NonCancellable) { deleteTestDirectory(root) } }
} }

private object ComposerHostLoginClient : OpenAiLoginClient {
    override fun authorizationUrl(request: OpenAiLoginAuthorization): String =
        "https://login.example.invalid"
    override suspend fun exchangeAuthorizationCode(
        request: OpenAiAuthorizationCodeExchange,
    ): OpenAiLoginResult<OpenAiSubscriptionTokens> = error("No login in Composer host tests.")
    override suspend fun refreshSubscriptionTokens(
        refreshToken: String,
    ): OpenAiLoginResult<OpenAiSubscriptionTokenRefresh> = error("No credentials in Composer host tests.")
}
