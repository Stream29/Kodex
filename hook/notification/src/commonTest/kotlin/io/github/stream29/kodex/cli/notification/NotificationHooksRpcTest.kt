package io.github.stream29.kodex.cli.notification

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.cli.settings.KodexNewSessionSettings
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.contract.AgentRuntimeRpc
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.inmemory.withInMemoryRpc
import io.github.stream29.kodex.rpc.models.*
import io.github.stream29.kodex.rpc.server.defaultBackendSettings
import io.github.stream29.kodex.rpc.server.withBackendServices
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellType
import io.github.stream29.kodex.utils.shellclient.default
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.rpc.withService
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** Real backend composition, JSON RPC and public shell executor; no live API, Home or user script. */
val notificationHooksRpcTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("real RPC stop runs local commands in order and cannot restore legacy control semantics") {
        withNotificationRpc { root, global, runtime, index, notifications, awaitSubscription ->
            val settings = MutableStateFlow(CliFrontendSettings(hooks = listOf(
                NotificationHook("unmatched", setOf(NotificationHookType.StopUnhandledError), "exit 99"),
                NotificationHook("failed", setOf(NotificationHookType.StopAssistantMessage), "exit 17"),
                NotificationHook("capture", setOf(NotificationHookType.StopAssistantMessage), captureCommand()),
            )))
            val collected = async(start = CoroutineStart.UNDISPATCHED) {
                collectNotificationHooks(notifications.take(1), { settings.value.hooks }, root)
            }
            try {
                awaitSubscription()
                runtime.submit(index, listOf(ContentItem.InputText("actual question")))
                runtime.getStateFlow(index).first { it == AgentStateValue.AssistantMessage }
                runtime.getRunningTurnFlow(index).first { !it }
                assertEquals(AgentStateValue.AssistantMessage, runtime.getState(index))
                assertFalse(runtime.getRunningTurn(index))
                collected.await()
                val received = Json.decodeFromString<Notification>(
                    SystemCoroutineFileSystem.readString(Path(root, "received.json")),
                )
                val stop = assertIs<Notification.Stop.AssistantMessage>(received)
                assertEquals(index, stop.sessionIndex)
                assertEquals(listOf(ContentItem.OutputText("literal answer")), stop.message.content)
                // The capture command writes old "continue" output; it must not inject a turn,
                // change history, make another provider request or modify runtime completion.
                assertEquals(AgentStateValue.AssistantMessage, runtime.getState(index))
                val catalog = global.getSessionCatalog(false)
                assertEquals(2, catalog.size) // actual Session and the isolated subscription probe
            } finally {
                collected.cancelAndJoin()
            }
        }
    }

    test("a real notification command timeout cleans up before the next command without failing the Agent") {
        withNotificationRpc { root, _, runtime, index, notifications, awaitSubscription ->
            val settings = MutableStateFlow(CliFrontendSettings(hooks = listOf(
                NotificationHook("slow", setOf(NotificationHookType.StopAssistantMessage), slowCommand()),
                NotificationHook("capture", setOf(NotificationHookType.StopAssistantMessage), captureCommand()),
            )))
            val collected = async(start = CoroutineStart.UNDISPATCHED) {
                collectNotificationHooks(notifications.take(1), { settings.value.hooks }, root)
            }
            try {
                awaitSubscription()
                runtime.submit(index, listOf(ContentItem.InputText("actual question")))
                runtime.getStateFlow(index).first { it == AgentStateValue.AssistantMessage }
                runtime.getRunningTurnFlow(index).first { !it }
                assertEquals(AgentStateValue.AssistantMessage, runtime.getState(index))
                assertFalse(runtime.getRunningTurn(index))
                collected.await() // includes the real ten-second timeout and process exit wait
                assertTrue(SystemCoroutineFileSystem.exists(Path(root, "slow-started")))
                assertFalse(SystemCoroutineFileSystem.exists(Path(root, "slow-finished")))
                val stop = Json.decodeFromString<Notification>(
                    SystemCoroutineFileSystem.readString(Path(root, "received.json")),
                )
                assertEquals(index, assertIs<Notification.Stop.AssistantMessage>(stop).sessionIndex)
                assertEquals(AgentStateValue.AssistantMessage, runtime.getState(index))
            } finally {
                collected.cancelAndJoin()
            }
        }
    }

    test("createAskUser emits AskUserCreated immediately without stopping or mutating the unanswered entry") {
        val questions = RequestUserInputArgs(questions = listOf(
            RequestUserInputQuestion("scope", "Scope", "Which scope?"),
            RequestUserInputQuestion("target", "Target", "Which target?"),
        ))
        withNotificationRpc(expectedRequests = 0) { root, _, runtime, index, notifications, awaitSubscription ->
            val settings = MutableStateFlow(CliFrontendSettings(hooks = listOf(NotificationHook(
                "capture", setOf(NotificationHookType.AskUserCreated),
                captureCommand(append = true),
            ))))
            val collected = async(start = CoroutineStart.UNDISPATCHED) {
                collectNotificationHooks(notifications.take(2), { settings.value.hooks }, root)
            }
            try {
                awaitSubscription()
                val created1 = runtime.createAskUser(index, questions)
                val created2 = runtime.createAskUser(index, questions)
                collected.await()
                val received = SystemCoroutineFileSystem.readString(Path(root, "received.json"))
                    .lineSequence().filter(String::isNotBlank).map { Json.decodeFromString<Notification>(it) }.toList()
                assertEquals(2, received.size)
                val ask1 = assertIs<Notification.AskUserCreated>(received[0])
                val ask2 = assertIs<Notification.AskUserCreated>(received[1])
                assertEquals(index, ask1.sessionIndex)
                assertEquals(index, ask2.sessionIndex)
                assertEquals(created1, ask1.entry)
                assertEquals(created2, ask2.entry)
                assertFalse(runtime.getRunningTurn(index))
            } finally {
                collected.cancelAndJoin()
            }
        }
    }
}

private suspend fun withNotificationRpc(
    calls: List<ResponseItem.ToolCall> = emptyList(),
    expectedRequests: Int = 1,
    block: suspend CoroutineScope.(Path, GlobalRpc, AgentRuntimeRpc, Int, Flow<Notification>, suspend () -> Unit) -> Unit,
) = withTimeout(45.seconds) {
    val root = Path(SystemTemporaryDirectory, "kodex-hook-rpc-${Random.nextLong()}")
    var actualRequests = 0
    val api = mockOpenAiClient {
        listModels { OpenAiResult.Success(ModelsResponse(listOf(ModelInfo(
            OpenAiModelId("test-model"), "Test Model", contextWindow = 100_000, maxContextWindow = 100_000,
        )))) }
        createResponse { request ->
            val probe = request.input.filterIsInstance<ResponseItem.Message>()
                .flatMap { it.content }.filterIsInstance<ContentItem.InputText>()
                .any { it.text == SubscriptionProbe }
            if (!probe) actualRequests++
            flow {
                if (!probe && calls.isNotEmpty()) {
                    calls.forEachIndexed { i, call -> emit(ResponsesStreamEvent.OutputItemDone(i.toLong(), call)) }
                } else {
                    emit(ResponsesStreamEvent.OutputItemDone(0, ResponseItem.Message(
                        role = MessageRole.Assistant, content = listOf(ContentItem.OutputText("literal answer")),
                    )))
                }
                emit(ResponsesStreamEvent.Completed(Response(id = "fixture", endTurn = probe || calls.isEmpty())))
            }
        }
    }
    try {
        SystemCoroutineFileSystem.createDirectories(root)
        withBackendServices(
            home = Path(root, "home"), codexHome = Path(root, "codex"), agentsHome = Path(root, "agents"),
            defaults = defaultBackendSettings().copy(
                sessionTitle = SessionTitleSettings(false),
                newSession = KodexNewSessionSettings(model = OpenAiModelId("test-model")),
            ),
            createClient = { api }, createLoginClient = { NoNetworkLogin },
        ) { backend ->
            withInMemoryRpc(backend::register) { raw ->
                val client = RestoringRpcClient(raw)
                val global = client.withService<GlobalRpc>()
                val runtime = client.withService<AgentRuntimeRpc>()
                // The isolated Home has no account; the model catalog starts with built-ins
                // and resolves the fake model through its normal fallback.
                global.getModelsFlow().first()
                val index = global.createSession(root)
                val probeIndex = global.createSession(root)
                runtime.keepAgentAlive(global.getRootAgentId(index))
                runtime.keepAgentAlive(global.getRootAgentId(probeIndex))
                val ready = CompletableDeferred<Unit>()
                val selected = backend.notifications
                    .onEach { notification ->
                        if (notification.targetSessionIndex == probeIndex) ready.complete(Unit)
                    }
                    .filter { it.targetSessionIndex == index }
                val awaitSubscription: suspend () -> Unit = {
                    withTimeout(10.seconds) {
                        while (!ready.isCompleted) {
                            runtime.submit(probeIndex, listOf(ContentItem.InputText(SubscriptionProbe)))
                            runtime.getStateFlow(probeIndex).first { it == AgentStateValue.AssistantMessage }
                            runtime.getRunningTurnFlow(probeIndex).first { !it }
                            if (!ready.isCompleted) delay(10)
                        }
                    }
                }
                block(root, global, runtime, index, selected, awaitSubscription)
                assertEquals(expectedRequests, actualRequests)
            }
        }
    } finally {
        withContext(NonCancellable) { removeNotificationFixture(root) }
    }
}

private val Notification.targetSessionIndex: Int
    get() = when (this) {
        is Notification.AskUserCreated -> sessionIndex
        is Notification.Stop -> sessionIndex
    }

private const val SubscriptionProbe = "isolated subscription probe"

private object NoNetworkLogin : OpenAiLoginClient {
    override fun authorizationUrl(request: OpenAiLoginAuthorization): String = error("No login in fixture")
    override suspend fun exchangeAuthorizationCode(
        request: OpenAiAuthorizationCodeExchange,
    ): OpenAiLoginResult<OpenAiSubscriptionTokens> = error("No credentials in fixture")
    override suspend fun refreshSubscriptionTokens(
        refreshToken: String,
    ): OpenAiLoginResult<OpenAiSubscriptionTokenRefresh> = error("No credentials in fixture")
}

private fun captureCommand(append: Boolean = false): String = when (Shell.default.type) {
    ShellType.Sh, ShellType.Bash, ShellType.Zsh ->
        if (append) "cat >> received.json; printf '\\n' >> received.json; printf '%s' '{\"action\":\"stop\"}'"
        else "cat > received.json; printf '%s' '{\"action\":\"continue\",\"prompt\":\"must not resume\"}'"
    ShellType.PowerShell ->
        "\$body = [Console]::In.ReadToEnd(); " +
            if (append) "[IO.File]::AppendAllText('received.json', \$body + [Environment]::NewLine); " +
                "[Console]::Out.Write('{\"action\":\"stop\"}')"
            else "[IO.File]::WriteAllText('received.json', \$body); " +
                "[Console]::Out.Write('{\"action\":\"continue\",\"prompt\":\"must not resume\"}')"
    ShellType.Cmd -> error("The isolated JSON command fixture requires POSIX shell or PowerShell")
}

private fun slowCommand(): String = when (Shell.default.type) {
    ShellType.Sh, ShellType.Bash, ShellType.Zsh ->
        "printf started > slow-started; sleep 30; printf finished > slow-finished"
    ShellType.PowerShell ->
        "[IO.File]::WriteAllText('slow-started', 'started'); Start-Sleep -Seconds 30; " +
            "[IO.File]::WriteAllText('slow-finished', 'finished')"
    ShellType.Cmd -> error("The isolated timeout fixture requires POSIX shell or PowerShell")
}

private suspend fun removeNotificationFixture(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) SystemCoroutineFileSystem.list(path).forEach { removeNotificationFixture(it) }
    SystemCoroutineFileSystem.delete(path)
}
