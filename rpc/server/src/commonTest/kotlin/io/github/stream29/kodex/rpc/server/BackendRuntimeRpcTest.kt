@file:Suppress("DEPRECATION")

package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agenteventbus.AgentEventBus
import io.github.stream29.kodex.agentsession.contract.KodexSessionRepository
import io.github.stream29.kodex.agentsession.sqlite.SqliteKodexSessionRepository
import io.github.stream29.kodex.agentstorage.sqlite.initializeKodexHomeDatabase
import io.github.stream29.kodex.agentstorage.sqlite.recomputeSessionCatalog
import io.github.stream29.kodex.utils.sqlite.openSqliteConnection
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.*
import io.github.stream29.kodex.agentstorage.contract.*
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.cli.sessiontitle.SessionTitleGenerationResult
import io.github.stream29.kodex.cli.sessiontitle.SessionTitleGenerator
import io.github.stream29.kodex.cli.sessiontitle.attachSessionTitleGeneration
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.client.rpcCachedAgentStorage
import io.github.stream29.kodex.rpc.contract.*
import io.github.stream29.kodex.rpc.inmemory.withInMemoryRpc
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputAnswer
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecProcessSession
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin as cancelScopeAndJoin
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.rpcexception.*
import io.github.stream29.kodex.utils.shellclient.StdoutSlice
import io.github.stream29.kodex.utils.shellclient.renderedBytes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.rpc.withService
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

val backendRuntimeRpcTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("checked shell capture stays on the original object after numeric reuse") {
        class Process(override val registrationNonce: String) : UnifiedExecProcessSession {
            override val sessionId = 42
            override val cwd = Path("/fixture")
            override val arguments = ExecCommandArguments("same command", name = "Exercise terminal access", yieldTimeMillis = 1_000)
            override val exitCode = MutableStateFlow<Int?>(null)
            var touched = 0
            override suspend fun readOutput(offset: ULong, limit: UInt): StdoutSlice {
                touched++
                return StdoutSlice(byteArrayOf(), byteArrayOf(), 0uL, offset, offset, offset)
            }
            override suspend fun writeStdin(chars: String, closeInput: Boolean): Nothing = error("Not a read or close")
            override fun close() { touched++ }
        }
        val original = Process("original")
        val replacement = Process("replacement")
        val registry = mutableMapOf<Int, UnifiedExecProcessSession>(42 to original)
        val captured = registry.registeredShell(42, original.registrationNonce)
        registry[42] = replacement
        assertFailsWith<ShellSessionNotRegistered> { registry.registeredShell(42, original.registrationNonce) }
        captured.readOutput(0uL, 64u)
        captured.close()
        assertEquals(2, original.touched)
        assertEquals(0, replacement.touched)
        assertSame(replacement, registry.registeredShell(42, null))
    }

    test("six concrete services retain real sparse values and frontend cache semantics") {
        runtimeFixture { repository, index, backend ->
            withInMemoryRpc(registerServices = {
                registerService(AgentRuntimeRpc::class) { backend }
            }) { raw ->
                val client = RestoringRpcClient(raw)
                val runtime = repository.openSessions.value.getValue(index).runtime
                runtime.appendUserMessage(listOf(ContentItem.InputText("one")))
                runtime.modify {
                    it.commit(
                        listOf(
                            StableReasoning(ResponseItem.Reasoning(encryptedContent = "test-reasoning")),
                            UnstableSnapshot(emptyList()),
                            LlmRequestMeta.Response(
                                totalTokens = 0,
                                elapsedNanoseconds = 1_000_000L,
                                outputTokens = 0,
                                usage = TokenUsage(0, 0, 0),
                                diagnostics = TokenCountDiagnostics(requestId = "test", turnStateReceived = true),
                            ),
                        ),
                    )
                }
                val storage = runtime.storage as ObservableKodexAgentStorage
                val rpc = client.withService<AgentRuntimeRpc>()
                checkTimeline(index, rpc, storage, AgentCommitType.IndexTypes) { it as IndexCommit }
                checkTimeline(index, rpc, storage, AgentCommitType.WorkTypes) { it as WorkCommit }
                checkTimeline(index, rpc, storage, AgentCommitType.SettingsTypes) { it as KodexAgentSettings }
                checkTimeline(index, rpc, storage, AgentCommitType.LlmRequestMetaTypes) { it as LlmRequestMeta }
                checkTimeline(index, rpc, storage, AgentCommitType.UnstableTypes) { (it as UnstableSnapshot).events }
                val nonce = rpc.getCacheNonce(index)
                assertEquals(
                    emptyList(),
                    (rpc.getFirstCommitsIn(
                        index,
                        nonce,
                        runtime.latestIndex.value,
                        runtime.latestIndex.value,
                        AgentCommitType.UnstableTypes,
                    ).singleOrNull()?.commit as? UnstableSnapshot)?.events,
                )
                assertEquals(emptyList(), rpc.getFirstCommitsIn(index, nonce, 999, 999, AgentCommitType.UnstableTypes))
            }
        }
    }

    test("settings CAS uses actual current value and equal writes leave the tail unchanged") {
        runtimeFixture { _, index, rpc ->
            val nonce = rpc.getCacheNonce(index)
            val old = rpc.getLastCommitsIn(
                index,
                nonce,
                0,
                rpc.getLatestIndex(index),
                AgentCommitType.SettingsTypes,
                1,
            ).single().commit as KodexAgentSettings
            val tail = rpc.getLatestIndex(index)
            assertTrue(rpc.compareAndSetSettings(index, old, old))
            assertEquals(tail, rpc.getLatestIndex(index))
            assertTrue(rpc.compareAndSetSettings(index, old, old.copy(name = "changed")))
            assertFalse(rpc.compareAndSetSettings(index, old, old.copy(name = "stale")))
            assertEquals(nonce, rpc.getCacheNonce(index))
        }
    }

    test("revert checks nonce at write admission clears steer and invalidates the frontend cache") {
        runtimeFixture { repository, index, rpc ->
            val session = repository.openSessions.value.getValue(index)
            session.runtime.appendUserMessage(listOf(ContentItem.InputText("old")))
            val nonce = rpc.getCacheNonce(index)
            session.runtime.steer(listOf(StableUserMessage(listOf(ContentItem.InputText("queued")))))
            assertFailsWith<CacheNonceMismatch> { rpc.revertHistory(index, 1, nonce xor 1) }
            assertFailsWith<IllegalArgumentException> { rpc.revertHistory(index, 0, nonce) }
            rpc.revertHistory(index, 1, nonce)
            assertEquals(0, rpc.getLatestIndex(index))
            assertEquals(emptyList(), session.storage.getSteerAt(rpc.getLatestIndex(index)))
            assertNotEquals(nonce, rpc.getCacheNonce(index))
            assertFailsWith<CacheNonceMismatch> { rpc.getFirstCommitsIn(index, nonce, 1, 1, AgentCommitType.IndexTypes) }
            session.runtime.appendUserMessage(listOf(ContentItem.InputText("replacement")))
            assertEquals(
                "replacement",
                (rpc.getFirstCommitsIn(
                    index,
                    rpc.getCacheNonce(index),
                    1,
                    1,
                    AgentCommitType.IndexTypes,
                ).single().commit as StableUserMessage)
                    .content.filterIsInstance<ContentItem.InputText>().single().text,
            )
        }
    }

    test("owner disappearance ends old RPC subscriptions without ending the connection") {
        runtimeFixture { repository, index, backend ->
            withInMemoryRpc(registerServices = { registerService(AgentRuntimeRpc::class) { backend } }) { raw ->
                val rpc = RestoringRpcClient(raw).withService<AgentRuntimeRpc>()
                val started = CompletableDeferred<Unit>()
                val subscription = async {
                    assertFailsWith<AgentNotActive> {
                        rpc.getLatestIndexFlow(index).onEach { started.complete(Unit) }.collect()
                    }
                }
                started.await()
                repository.delete(index)
                subscription.await()
                assertFailsWith<AgentNotFound> { rpc.getLatestIndex(index) }
                val replacement = repository.create()
                repository.open(replacement)
                assertEquals(-1, rpc.getLatestIndex(repository.getRootAgentId(replacement)))
            }
        }
    }

    test("stream nonce binds replay and live collection to one original output") {
        val item = ResponseItem.Message(id = ResponseItemId("message"), role = MessageRole.Assistant, content = emptyList())
        val added = ResponsesStreamEvent.OutputItemAdded(0, item)
        val release = CompletableDeferred<Unit>()
        val done = ResponsesStreamEvent.OutputItemDone(0, item.copy(content = listOf(ContentItem.OutputText("answer"))))
        val client = mockOpenAiClient {
            createResponse {
                flow {
                    emit(added)
                    release.await()
                    emit(done)
                    emit(ResponsesStreamEvent.Completed(Response(id = "response", endTurn = true)))
                }
            }
        }
        runtimeFixture(client) { repository, index, backend ->
            withInMemoryRpc(registerServices = { registerService(AgentRuntimeRpc::class) { backend } }) { raw ->
                val rpc = RestoringRpcClient(raw).withService<AgentRuntimeRpc>()
                repository.openSessions.value.getValue(index).runtime.appendUserMessage(listOf(ContentItem.InputText("question")))
                val waiter = async { rpc.resume(index) }
                val active = rpc.getStateFlow(index).filterIsInstance<AgentStateValue.RequestResponse.Message>().first()
                val replay = rpc.currentFlow(index, active.nonce).first()
                assertEquals(added, replay)
                assertEquals(active, rpc.getState(index))
                assertTrue(rpc.getRunningTurn(index))
                assertFailsWith<NoMatchException> { rpc.currentFlow(index, active.nonce xor 1).first() }
                val liveSubscribed = CompletableDeferred<Unit>()
                val values = async(start = CoroutineStart.UNDISPATCHED) {
                    rpc.currentFlow(index, active.nonce)
                        .onEach { liveSubscribed.complete(Unit) }
                        .take(2)
                        .toList()
                }
                liveSubscribed.await()
                waiter.cancelAndJoin()
                assertTrue(repository.openSessions.value.getValue(index).runtime.runningTurn.value != null)
                release.complete(Unit)
                assertEquals(listOf(added, done), values.await())
                rpc.getRunningTurnFlow(index).first { !it }
                assertFailsWith<NoMatchException> { rpc.currentFlow(index, active.nonce).first() }
                assertEquals(AgentStateValue.AssistantMessage, rpc.getState(index))
            }
        }
    }

    test("async resume acknowledges its own admission while the model is still blocked") {
        val entered = CompletableDeferred<Unit>()
        val client = mockOpenAiClient { createResponse { flow { entered.complete(Unit); awaitCancellation() } } }
        runtimeFixture(client) { repository, index, backend ->
            val session = repository.openSessions.value.getValue(index)
            assertFalse(backend.requestResume(index), "An empty Agent cannot admit a new turn.")
            session.runtime.appendUserMessage(listOf(ContentItem.InputText("fixture question")))
            val messages = setOf(AgentCommitType.UserMessage)
            val before = session.storage.countCommitsIn(0..Int.MAX_VALUE, messages)
            withInMemoryRpc(registerServices = { registerService(AgentRuntimeRpc::class) { backend } }) { raw ->
                val rpc = RestoringRpcClient(raw).withService<AgentRuntimeRpc>()
                assertTrue(rpc.requestResume(index))
                entered.await()
                assertFalse(rpc.requestResume(index), "A concurrent RPC cannot claim the occupied slot.")
                assertFailsWith<IllegalStateException> { backend.resume(index) }
            }
            assertTrue(backend.getRunningTurn(index), "Closing the admitting connection must not stop the turn.")
            assertEquals(before, session.storage.countCommitsIn(0..Int.MAX_VALUE, messages), "Resume must not append a user message.")
            backend.cancelRunningTurn(index)
            backend.getRunningTurnFlow(index).first { !it }
            backend.cancelRunningTurn(index)
        }
    }

    test("an archived root can still resume while its completed bit is set") {
        val entered = CompletableDeferred<Unit>()
        val client = mockOpenAiClient { createResponse { flow { entered.complete(Unit); awaitCancellation() } } }
        runtimeFixture(client) { repository, index, backend ->
            val entry = repository.getEntry(index)
            entry.archive()
            assertTrue(repository.getEntry(index).archived)
            assertTrue(checkNotNull(repository.getAgentRecord(entry.rootAgentId)).completed)
            val session = repository.openSessions.value.getValue(index)
            session.runtime.appendUserMessage(listOf(ContentItem.InputText("resume archived root")))
            withInMemoryRpc(registerServices = { registerService(AgentRuntimeRpc::class) { backend } }) { raw ->
                val rpc = RestoringRpcClient(raw).withService<AgentRuntimeRpc>()
                assertTrue(rpc.requestResume(entry.rootAgentId))
                entered.await()
                rpc.cancelRunningTurn(entry.rootAgentId)
                rpc.getRunningTurnFlow(entry.rootAgentId).first { !it }
            }
            assertTrue(repository.getEntry(index).archived)
        }
    }

    test("stop cancels current operation while a cancelled waiter does not") {
        val entered = CompletableDeferred<Unit>()
        val client = mockOpenAiClient {
            createResponse {
                flow {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        runtimeFixture(client) { repository, index, rpc ->
            repository.openSessions.value.getValue(index).runtime.appendUserMessage(listOf(ContentItem.InputText("run")))
            val waiter = async { rpc.resume(index) }
            entered.await()
            assertFailsWith<IllegalStateException> { rpc.resume(index) }
            waiter.cancelAndJoin()
            assertTrue(rpc.getRunningTurn(index))
            rpc.cancelRunningTurn(index)
            rpc.getRunningTurnFlow(index).first { !it }
            rpc.cancelRunningTurn(index)
            assertEquals(AgentStateValue.UserMessage, rpc.getState(index))
        }
    }

    test("manual compaction shares Stop and does not consume queued steer") {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = mockOpenAiClient {
            createRemoteCompactionV2Response { _ ->
                entered.complete(Unit)
                release.await()
                RemoteCompactionV2Response(ResponseItem.Compaction(encryptedContent = "test"), null)
            }
        }
        runtimeFixture(client) { repository, index, rpc ->
            repository.openSessions.value.getValue(index).runtime.appendUserMessage(listOf(ContentItem.InputText("compact")))
            val operation = async { rpc.forcedCompact(index) }
            entered.await()
            assertEquals(AgentStateValue.Compacting, rpc.getState(index))
            assertTrue(rpc.getRunningTurn(index))
            assertFailsWith<NoMatchException> { rpc.currentFlow(index, 0).first() }
            assertFailsWith<IllegalStateException> { rpc.resume(index) }
            val pending = listOf(StableUserMessage(listOf(ContentItem.InputText("queued"))))
            rpc.submit(index, listOf(ContentItem.InputText("queued")))
            release.complete(Unit)
            assertTrue(operation.await() > 1)
            assertEquals(pending, repository.openSessions.value.getValue(index).storage.getSteerAt(rpc.getLatestIndex(index)))
            assertFalse(rpc.getRunningTurn(index))
        }
    }

    test("real RPC settings CAS accepted during compaction survives its published checkpoint") {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = mockOpenAiClient {
            createRemoteCompactionV2Response {
                entered.complete(Unit)
                release.await()
                RemoteCompactionV2Response(ResponseItem.Compaction(encryptedContent = "compacted"), null)
            }
        }
        runtimeFixture(client) { repository, index, backendRuntime ->
            withInMemoryRpc(registerServices = {
                registerService(AgentRuntimeRpc::class) { backendRuntime }
            }) { raw ->
                val restored = RestoringRpcClient(raw)
                val runtime = restored.withService<AgentRuntimeRpc>()
                repository.openSessions.value.getValue(index).runtime.appendUserMessage(listOf(ContentItem.InputText("compact")))
                val before = runtime.getLastCommitsIn(
                    index,
                    runtime.getCacheNonce(index),
                    0,
                    runtime.getLatestIndex(index),
                    AgentCommitType.SettingsTypes,
                    1,
                ).single().commit as KodexAgentSettings
                val operation = async { runtime.forcedCompact(index) }
                try {
                    entered.await()
                    val first = before.copy(name = "accepted-during-compaction")
                    assertTrue(runtime.compareAndSetSettings(index, before, first))
                    val latest = first.copy(model = OpenAiModelId("updated-model"))
                    assertTrue(runtime.compareAndSetSettings(index, first, latest))
                    release.complete(Unit)
                    val checkpoint = operation.await()
                    val published = runtime.getLastCommitsIn(
                        index,
                        runtime.getCacheNonce(index),
                        0,
                        checkpoint,
                        AgentCommitType.SettingsTypes,
                        1,
                    ).single().commit as KodexAgentSettings
                    assertEquals(latest.name, published.name)
                    assertEquals(latest.model, published.model)
                    assertEquals(
                        checkpoint,
                        runtime.getLastCommitsIn(
                            index,
                            runtime.getCacheNonce(index),
                            0,
                            checkpoint,
                            AgentCommitType.SettingsTypes,
                            1,
                        ).single().index,
                    )
                    assertEquals(AgentStateValue.UserMessage, runtime.getState(index))
                } finally {
                    release.complete(Unit)
                    operation.cancelAndJoin()
                }
            }
        }
    }

    test("shell completion output cursor and stdin write work over RPC") {
        runtimeFixture { repository, index, backend ->
            withInMemoryRpc(registerServices = { registerService(AgentRuntimeRpc::class) { backend } }) { raw ->
                val rpc = RestoringRpcClient(raw).withService<AgentRuntimeRpc>()
                val shell = repository.openSessions.value.getValue(index).runtime.unifiedExecToolClient
                val result = withContext(Dispatchers.Default) {
                    shell.execCommand(
                        io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments(
                            command = "printf 'ready\\n'; read line; printf 'got:%s\\n' \"\$line\"",
                            name = "Exercise the RPC terminal",
                            yieldTimeMillis = 250,
                        ),
                    )
                }
                val id = assertNotNull(result.sessionId)
                val initialState = rpc.getShellSessions(index).getValue(id)
                assertTrue(initialState.arguments.command.isNotEmpty())
                assertNull(initialState.exitCode)
                assertFalse(initialState.completed)
                assertTrue(initialState.registrationNonce.isNotBlank())
                assertFailsWith<ShellSessionNotRegistered> {
                    rpc.closeShellSession(index, id, expectedRegistrationNonce = "replaced-registration")
                }
                assertFailsWith<ShellSessionNotRegistered> {
                    rpc.readShellSessionOutput(index, id, expectedRegistrationNonce = "replaced-registration")
                }
                val running = rpc.readShellSessionOutput(index, id, expectedRegistrationNonce = initialState.registrationNonce)
                assertEquals(0uL, running.offset)

                rpc.writeShellSessionStdin(index, id, "hello\n", closeInput = true)
                val finalMap = rpc.getShellSessionsFlow(index).first { it[id]?.completed == true }
                val finalState = finalMap.getValue(id)
                assertEquals(0, finalState.exitCode)
                assertTrue(finalState.completed)
                assertEquals(initialState.registrationNonce, finalState.registrationNonce)

                val slice = rpc.readShellSessionOutput(index, id, offset = 0uL, limit = 4096u)
                val text = slice.renderedBytes().decodeToString()
                assertTrue(text.contains("ready"))
                assertTrue(text.contains("got:hello"))
                assertTrue(slice.nextOffset > 0uL)
                assertEquals(slice, rpc.readShellSessionOutput(index, id, offset = 0uL, limit = 4096u))
                assertTrue(id in rpc.getShellSessions(index))
                assertFailsWith<ShellSessionNotRegistered> { rpc.closeShellSession(index, -1) }
                assertFailsWith<ShellSessionNotRegistered> { rpc.readShellSessionOutput(index, -1) }
                val writeError = assertFails { rpc.writeShellSessionStdin(index, -1, "hi") }
                assertTrue(writeError.message.orEmpty().contains("Shell session is no longer registered"))
            }
        }
    }

    test("submitAskUserAnswer commits AskUserSnapshot and wakes parked await_event") {
        var callCount = 0
        val client = mockOpenAiClient {
            createResponse {
                callCount++
                when (callCount) {
                    1 -> flowOf(
                        ResponsesStreamEvent.OutputItemDone(
                            0,
                            ResponseItem.FunctionCall(
                                name = "await_event",
                                arguments = """{"name":"Wait for user input","events":[{"type":"user_message"}]}""",
                                callId = "await_1",
                            ),
                        ),
                        ResponsesStreamEvent.Completed(Response(id = "resp_1", endTurn = false)),
                    )

                    else -> flowOf(
                        ResponsesStreamEvent.OutputItemDone(
                            0,
                            ResponseItem.Message(
                                id = ResponseItemId("msg_2"),
                                role = MessageRole.Assistant,
                                content = listOf(ContentItem.OutputText("received answer")),
                            ),
                        ),
                        ResponsesStreamEvent.Completed(Response(id = "resp_2", endTurn = true)),
                    )
                }
            }
        }
        runtimeFixture(client) { repository, index, backend ->
            withInMemoryRpc(registerServices = { registerService(AgentRuntimeRpc::class) { backend } }) { raw ->
                val rpc = RestoringRpcClient(raw).withService<AgentRuntimeRpc>()
                val session = repository.openSessions.value.getValue(index)
                val createdAsk = session.runtime.createAskUser(
                    RequestUserInputArgs(
                        questions = listOf(
                            RequestUserInputQuestion(id = "q1", header = "H", question = "Pick one?"),
                        ),
                    ),
                )
                session.runtime.appendUserMessage(listOf(ContentItem.InputText("wait for my answer")))
                rpc.resume(index)
                assertFalse(rpc.getRunningTurn(index))
                assertIs<AgentStateValue.ToolPending>(rpc.getState(index))

                val response = RequestUserInputResponse(
                    answers = mapOf("q1" to RequestUserInputAnswer(listOf("yes"))),
                )
                rpc.submitAskUserAnswer(index, createdAsk.askId, response)
                withTimeout(5.seconds) {
                    rpc.getStateFlow(index).first { it == AgentStateValue.AssistantMessage }
                }
                rpc.getRunningTurnFlow(index).first { !it }
                val latest = session.storage.latestIndex()
                val entry = session.storage.getAskUsersAt(latest).single()
                assertEquals(createdAsk.askId, entry.askId)
                assertEquals(response, entry.answer?.response)
            }
        }
    }

    test("createAskUser rejects NoQuestion mode and runWebSearch uses session model") {
        var capturedSearchModel: OpenAiModelId? = null
        val client = mockOpenAiClient {
            search { request ->
                capturedSearchModel = request.model
                OpenAiResult.Success(
                    SearchResponse(
                        output = "search result",
                    ),
                )
            }
        }
        runtimeFixture(client) { repository, index, backend ->
            withInMemoryRpc(registerServices = { registerService(AgentRuntimeRpc::class) { backend } }) { raw ->
                val rpc = RestoringRpcClient(raw).withService<AgentRuntimeRpc>()
                val session = repository.openSessions.value.getValue(index)
                val searchResponse = rpc.runWebSearch(
                    index,
                    SearchCommands(searchQuery = listOf(SearchQuery(q = "kodex"))),
                )
                assertEquals(OpenAiModelId("test-model"), capturedSearchModel)
                assertEquals("search result", searchResponse.output)

                val current = session.storage.getSettingsAt(session.storage.latestIndex())
                session.runtime.compareAndSetSettings(
                    current,
                    current.copy(requestUserInputMode = RequestUserInputMode.NoQuestion),
                )
                assertFails {
                    rpc.createAskUser(
                        index,
                        RequestUserInputArgs(
                            questions = listOf(RequestUserInputQuestion("q1", "H", "Blocked?")),
                        ),
                    )
                }
            }
        }
    }

    test("projection changes nonce only for stream identity and never offers compaction output") {
        val projection = RuntimeOutputProjection()
        val first = MutableSharedFlow<ResponsesStreamEvent>(replay = 4)
        val second = MutableSharedFlow<ResponsesStreamEvent>(replay = 4)
        val a = projection.snapshot { KodexAgentStateValue.RequestResponse.Message(first) }
        val b = projection.snapshot { KodexAgentStateValue.RequestResponse.Message(first) }
        assertEquals(a.nonce, b.nonce)
        assertSame(first, a.events)
        val c = projection.snapshot { KodexAgentStateValue.RequestResponse.Reasoning(second) }
        assertNotEquals(a.nonce, c.nonce)
        val compact = projection.snapshot { KodexAgentStateValue.Compacting }
        assertEquals(AgentStateValue.Compacting, compact.value)
        assertNull(compact.events)
        assertNull(projection.snapshot { KodexAgentStateValue.RequestResponse.Started }.events)
    }

    test("automatic title preserves a winning manual name without repeating the model") {
        val entered = CompletableDeferred<Unit>()
        val titleJob = CompletableDeferred<Job>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        runtimeFixture(
            generator = SessionTitleGenerator { _, _, _ ->
                calls++
                titleJob.complete(currentCoroutineContext().job)
                entered.complete(Unit)
                release.await()
                SessionTitleGenerationResult.Generated("automatic")
            },
            settings = SessionTitleSettings(),
        ) { repository, index, rpc ->
            val session = repository.openSessions.value.getValue(index)
            session.runtime.appendUserMessage(listOf(ContentItem.InputText("title")))
            entered.await()
            val nonce = rpc.getCacheNonce(index)
            val old = rpc.getLastCommitsIn(
                index,
                nonce,
                0,
                rpc.getLatestIndex(index),
                AgentCommitType.SettingsTypes,
                1,
            ).single().commit as KodexAgentSettings
            assertTrue(rpc.compareAndSetSettings(index, old, old.copy(name = "manual")))
            release.complete(Unit)
            titleJob.await().join()
            session.runtime.appendUserMessage(listOf(ContentItem.InputText("second")))
            assertEquals(
                "manual",
                (rpc.getLastCommitsIn(
                    index,
                    nonce,
                    0,
                    rpc.getLatestIndex(index),
                    AgentCommitType.SettingsTypes,
                    1,
                ).single().commit as KodexAgentSettings).name,
            )
            assertEquals(1, calls)
        }
    }

    test("closing the backend Session owner cancels its pending title request") {
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val job = CompletableDeferred<Job>()
        runtimeFixture(
            generator = SessionTitleGenerator { _, _, _ ->
                job.complete(currentCoroutineContext().job)
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            },
            settings = SessionTitleSettings(),
        ) { repository, index, rpc ->
            assertEquals(1, repository.openSessions.value.getValue(index).runtime.appendUserMessage(listOf(ContentItem.InputText("Pending title"))))
            entered.await()
            repository.delete(index)
            stopped.await()
            job.await().join()
            assertTrue(job.await().isCancelled)
            assertFailsWith<AgentNotFound> { rpc.getLatestIndex(index) }
        }
    }

    test("automatic title keeps unrelated setting updates and failed titles do not fail append") {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        runtimeFixture(
            generator = SessionTitleGenerator { _, _, _ ->
                entered.complete(Unit)
                release.await()
                SessionTitleGenerationResult.Generated("automatic")
            },
            settings = SessionTitleSettings(),
        ) { repository, index, rpc ->
            repository.openSessions.value.getValue(index).runtime.appendUserMessage(listOf(ContentItem.InputText("title")))
            entered.await()
            val nonce = rpc.getCacheNonce(index)
            val old = rpc.getLastCommitsIn(
                index,
                nonce,
                0,
                rpc.getLatestIndex(index),
                AgentCommitType.SettingsTypes,
                1,
            ).single().commit as KodexAgentSettings
            val changed = old.copy(instructions = "new instructions")
            assertTrue(rpc.compareAndSetSettings(index, old, changed))
            release.complete(Unit)
            rpc.getLatestIndexFlow(index).first {
                (rpc.getLastCommitsIn(index, nonce, 0, it, AgentCommitType.SettingsTypes, 1)
                    .single().commit as KodexAgentSettings).name == "automatic"
            }
            assertEquals(
                changed.instructions,
                (rpc.getLastCommitsIn(
                    index,
                    nonce,
                    0,
                    rpc.getLatestIndex(index),
                    AgentCommitType.SettingsTypes,
                    1,
                ).single().commit as KodexAgentSettings).instructions,
            )
        }
        runtimeFixture(
            generator = SessionTitleGenerator { _, _, _ -> error("optional title failure") },
            settings = SessionTitleSettings(),
        ) { repository, index, rpc ->
            assertEquals(1, repository.openSessions.value.getValue(index).runtime.appendUserMessage(listOf(ContentItem.InputText("persist"))))
            assertEquals(AgentStateValue.UserMessage, rpc.getState(index))
        }
    }
}

private suspend fun <T : Any> CoroutineScope.checkTimeline(
    index: Int,
    rpc: AgentRuntimeRpc,
    storage: ObservableKodexAgentStorage,
    types: Set<AgentCommitType>,
    extract: (AgentCommit) -> T,
) {
    val nonce = rpc.getCacheNonce(index)
    val expectedValues = storage.getCommitsIn(0..100, types).map { it.index to extract(it.commit) }
    val expectedLatest = expectedValues.lastOrNull()?.first ?: -1
    assertEquals(storage.cacheNonce.value, nonce)
    assertEquals(storage.latestIndex(), rpc.getLatestIndex(index))
    assertEquals(expectedValues.size, rpc.countCommitsIn(index, nonce, 0, 100, types))
    assertEquals(expectedValues, rpc.getFirstCommitsIn(index, nonce, 0, 100, types).map { it.index to extract(it.commit) })
    assertEquals(emptyList(), rpc.getFirstCommitsIn(index, nonce, 4, 3, types))
    assertEquals(expectedLatest.takeIf { it >= 0 }, rpc.getLastCommitsIn(index, nonce, 0, 100, types, 1).lastOrNull()?.index)
    assertEquals(expectedValues.firstOrNull()?.first, rpc.getFirstCommitsIn(index, nonce, 0, 100, types, 1).firstOrNull()?.index)
    assertEquals(emptyList(), rpc.getFirstCommitsIn(index, nonce, 100, 100, types))
    assertFailsWith<CacheNonceMismatch> { rpc.getFirstCommitsIn(index, nonce xor 1, 0, 0, types) }
    val job = Job(coroutineContext[Job])
    try {
        val cached = CoroutineScope(coroutineContext + job).rpcCachedAgentStorage(index, rpc)
        for ((position, value) in expectedValues) {
            assertEquals(value, extract(checkNotNull(cached.getCommitExact(position, types)).commit))
            assertEquals(value, extract(cached.getLastCommitsIn(0..position, types, 1).single().commit))
        }
    } finally {
        job.cancelAndJoin()
    }
}

internal suspend fun runtimeFixture(
    client: OpenAiClient = mockOpenAiClient(),
    generator: SessionTitleGenerator = SessionTitleGenerator { _, _, _ -> error("Titles disabled in fixture") },
    settings: SessionTitleSettings = SessionTitleSettings(enabled = false),
    block: suspend CoroutineScope.(KodexSessionRepository, Int, BackendAgentRuntimeRpc) -> Unit,
) = withTimeout(30.seconds) {
    val root = Path(SystemTemporaryDirectory, "kodex-rpc-runtime-${Random.nextLong()}")
    val repoOwner = CoroutineScope(coroutineContext + SupervisorJob())
    try {
        // Keep the fixtures' existing root Agent ID 0 while preparing the live schema.
        SystemCoroutineFileSystem.createDirectories(root)
        openSqliteConnection(Path(root, "kodex.db")).use { db ->
            initializeKodexHomeDatabase(db)
            db.execute("INSERT INTO sessions(session_id) VALUES(0)")
            db.execute("INSERT INTO agents(agent_id, session_id) VALUES(0, 0)")
            db.execute("INSERT INTO session_catalog_view(root_agent_id) VALUES(0)")
            db.recomputeSessionCatalog(0)
        }
        val repository = repoOwner.SqliteKodexSessionRepository(root, testKodexAgentDependencies(client))
        try {
            val eventBus = AgentEventBus(repository)
            val index = 0
            val session = repository.open(index)
            session.runtime.modify {
                it.initialize(KodexAgentSettings(model = OpenAiModelId("test-model"), name = "Session $index"))
            }
            attachSessionTitleGeneration(
                scope = session.runtime,
                agentState = session.runtime,
                settings = { settings },
                generator = generator,
            )
            coroutineScope {
                block(
                    repository,
                    index,
                    BackendAgentRuntimeRpc(
                        repository = repository,
                        eventBus = eventBus,
                        client = client,
                        home = root,
                    ),
                )
            }
        } finally {
            withContext(NonCancellable) {
                repository.closeAndJoin()
            }
        }
    } finally {
        withContext(NonCancellable) {
            repoOwner.cancelScopeAndJoin()
            suspend fun delete(path: Path) {
                val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
                if (metadata.isDirectory) SystemCoroutineFileSystem.list(path).forEach { delete(it) }
                SystemCoroutineFileSystem.delete(path)
            }
            delete(root)
        }
    }
}
