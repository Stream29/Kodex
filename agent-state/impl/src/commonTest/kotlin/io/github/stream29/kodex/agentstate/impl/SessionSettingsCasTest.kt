package io.github.stream29.kodex.agentstate.impl

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.contract.MutableIndexVersioned
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ResponsesApiRequest
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.openai.client.contract.OpenAiResponseHeaders
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

val sessionSettingsCasTest by testSuite {
    testFixture {
        testSuiteCoroutineScope.supervisorChildScope()
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
        test("equal distinct expect appends settings and timestamp without replacing history") {
            val storage = casStorage()
            val agent = KodexAgentState(mockOpenAiClient(), storage)
            val old = storage.settings[0]
            val expect = old.copy()
            assertNotSame(old, expect)
            val update = old.copy(threadName = "renamed")
            val oldTimestamp = storage.timestamp[0]

            assertTrue(agent.compareAndSetSettings(expect, update))

            assertEquals(update, storage.settings[1])
            assertEquals(old, storage.settings.getExact(0))
            assertEquals(oldTimestamp, storage.timestamp.getExact(0))
            assertEquals(listOf(0, 1), storage.settings.indexesIn(0..2))
            assertEquals(listOf(0, 1), storage.timestamp.indexesIn(0..2))
            assertEquals(1, agent.latestIndex.value)
            assertEquals(KodexAgentStateValue.Empty, agent.state.value)
        }

        test("mismatch compares the complete value and performs no append") {
            val storage = casStorage()
            val agent = KodexAgentState(mockOpenAiClient(), storage)
            val current = storage.settings[0]
            val timestamps = storage.timestamp.valuesIn(0..10)

            assertFalse(agent.compareAndSetSettings(current.copy(turnState = "stale"), current))
            assertFalse(agent.compareAndSetSettings(current.copy(threadName = "stale"), current))

            assertEquals(listOf(0 to current), storage.settings.valuesIn(0..10))
            assertEquals(timestamps, storage.timestamp.valuesIn(0..10))
            assertEquals(0, agent.latestIndex.value)
        }

        test("equal update succeeds without settings or timestamp writes") {
            val backing = casStorage()
            val guarded = interceptSettingsWrites(backing) { _, _ -> error("Unexpected write") }
            val agent = KodexAgentState(mockOpenAiClient(), guarded)
            val current = backing.settings[0]
            val timestamps = backing.timestamp.valuesIn(0..10)

            assertTrue(agent.compareAndSetSettings(current.copy(), current.copy()))
            assertEquals(listOf(0), backing.settings.indexesIn(0..10))
            assertEquals(timestamps, backing.timestamp.valuesIn(0..10))
            assertEquals(0, agent.latestIndex.value)
        }

        test("competing CAS calls share one comparison boundary") {
            val backing = casStorage()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val storage = interceptSettingsWrites(backing) { _, _ ->
                entered.complete(Unit)
                release.await()
            }
            val agent = KodexAgentState(mockOpenAiClient(), storage)
            val current = backing.settings[0]
            val calls = (1..8).map { n ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    agent.compareAndSetSettings(current, current.copy(threadName = "name-$n"))
                }
            }
            entered.await()
            assertTrue(calls.none { it.isCompleted })
            release.complete(Unit)

            assertEquals(listOf(true) + List(7) { false }, calls.awaitAll())
            assertEquals("name-1", backing.settings[1].threadName)
            assertEquals(listOf(0, 1), backing.timestamp.indexesIn(0..10))
        }

        test("unconditional update holds the same lock and invalidates queued expect") {
            val backing = casStorage()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val storage = interceptSettingsWrites(backing) { _, _ ->
                entered.complete(Unit)
                release.await()
            }
            val agent = KodexAgentState(mockOpenAiClient(), storage)
            val current = backing.settings[0]
            val update = current.copy(threadName = "unconditional")
            val writer = async(start = CoroutineStart.UNDISPATCHED) { agent.updateSettings(update) }
            entered.await()
            val cas = async(start = CoroutineStart.UNDISPATCHED) {
                agent.compareAndSetSettings(current, current.copy(threadName = "obsolete"))
            }
            assertFalse(cas.isCompleted)
            release.complete(Unit)

            assertEquals(1, writer.await())
            assertFalse(cas.await())
            assertEquals(update, backing.settings[backing.latestIndex()])
        }

        test("CAS holds the same lock until the append completes") {
            val backing = casStorage()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val storage = interceptSettingsWrites(backing) { index, _ ->
                if (index == 1) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            val agent = KodexAgentState(mockOpenAiClient(), storage)
            val current = backing.settings[0]
            val update = current.copy(threadName = "cas")
            val cas = async(start = CoroutineStart.UNDISPATCHED) {
                agent.compareAndSetSettings(current, update)
            }
            entered.await()
            val writer = async(start = CoroutineStart.UNDISPATCHED) {
                agent.updateSettings(update.copy(threadName = "next"))
            }
            assertFalse(writer.isCompleted)
            assertEquals(0, agent.latestIndex.value)
            release.complete(Unit)

            assertTrue(cas.await())
            assertEquals(2, writer.await())
            assertEquals(update, backing.settings.getExact(1))
            assertEquals("next", backing.settings[2].threadName)
        }

        test("cancelled lock waiter never compares or writes") {
            val storage = casStorage()
            val agent = KodexAgentState(mockOpenAiClient(), storage)
            val current = storage.settings[0]
            val release = CompletableDeferred<Unit>()
            val holder = async(start = CoroutineStart.UNDISPATCHED) {
                agent.modify { release.await() }
            }
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                agent.compareAndSetSettings(current, current.copy(threadName = "cancelled"))
            }
            assertFalse(waiter.isCompleted)
            waiter.cancel()
            waiter.join()
            release.complete(Unit)
            holder.await()

            assertEquals(0, storage.latestIndex())
            assertEquals(current, storage.settings[0])
            assertTrue(agent.compareAndSetSettings(current, current.copy(threadName = "next")))
        }

        test("write failure propagates and releases the comparison lock") {
            val backing = casStorage()
            val failure = IllegalStateException("injected storage failure")
            var fail = true
            val storage = interceptSettingsWrites(backing) { _, _ ->
                if (fail) throw failure
            }
            val agent = KodexAgentState(mockOpenAiClient(), storage)
            val current = backing.settings[0]
            val update = current.copy(threadName = "new")

            assertSame(failure, assertFailsWith<IllegalStateException> {
                agent.compareAndSetSettings(current, update)
            })
            assertEquals(0, agent.latestIndex.value)
            assertEquals(0, backing.latestIndex())
            assertEquals(KodexAgentStateValue.Empty, agent.state.value)
            fail = false
            assertTrue(agent.compareAndSetSettings(current, update))
        }

        test("cancellation during the settings append propagates without a false result") {
            val backing = casStorage()
            val entered = CompletableDeferred<Unit>()
            var suspendWrite = true
            val storage = interceptSettingsWrites(backing) { _, _ ->
                if (suspendWrite) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            val agent = KodexAgentState(mockOpenAiClient(), storage)
            val current = backing.settings[0]
            val call = async(start = CoroutineStart.UNDISPATCHED) {
                agent.compareAndSetSettings(current, current.copy(threadName = "cancelled"))
            }
            entered.await()
            call.cancel()
            assertFailsWith<CancellationException> { call.await() }
            assertEquals(0, agent.latestIndex.value)
            assertEquals(current, backing.settings[0])
            suspendWrite = false
            assertTrue(agent.compareAndSetSettings(current, current.copy(threadName = "next")))
        }

        test("comparison read errors propagate rather than reporting mismatch") {
            val backing = casStorage()
            val failure = IllegalStateException("injected read failure")
            var fail = false
            val storage = object : MutableKodexAgentStorage by backing {
                override val settings = object : MutableIndexVersioned<KodexAgentSettings> by backing.settings {
                    override suspend fun get(index: Int): KodexAgentSettings {
                        if (fail) throw failure
                        return backing.settings[index]
                    }
                }
            }
            val agent = KodexAgentState(mockOpenAiClient(), storage)
            val current = backing.settings[0]
            fail = true
            assertSame(failure, assertFailsWith<IllegalStateException> {
                agent.compareAndSetSettings(current, current)
            })
            fail = false
            assertTrue(agent.compareAndSetSettings(current, current))
        }

        for (headersFirst in listOf(true, false)) {
            test("response headers and CAS preserve each other with headersFirst=$headersFirst") {
                val storage = casStorage()
                val ready = CompletableDeferred<Unit>()
                val deliverHeaders = CompletableDeferred<Unit>()
                val headersStored = CompletableDeferred<Unit>()
                val requests = mutableListOf<ResponsesApiRequest>()
                val client = object : OpenAiClient by mockOpenAiClient() {
                    override suspend fun createResponse(
                        request: ResponsesApiRequest,
                        installationId: String?,
                        turnMetadata: String,
                        windowId: String,
                        turnState: String?,
                        onResponseHeaders: suspend (OpenAiResponseHeaders) -> Unit,
                    ): Flow<ResponsesStreamEvent> = flow {
                        requests += request
                        ready.complete(Unit)
                        deliverHeaders.await()
                        onResponseHeaders(OpenAiResponseHeaders("received-turn-state", "request"))
                        headersStored.complete(Unit)
                        awaitCancellation()
                    }
                }
                val agent = KodexAgentState(client, storage)
                agent.appendUserMessage(listOf(ContentItem.InputText("Test settings editing.")))
                val settingsIndex = storage.settings.latestIndex()
                val current = storage.settings[storage.latestIndex()]
                val updatedModel = OpenAiModelId("updated")
                val update = current.copy(model = updatedModel)
                val running = async(start = CoroutineStart.UNDISPATCHED) { agent.requestResponseApi() }
                ready.await()
                val requestState = agent.state.value
                assertIs<KodexAgentStateValue.RequestResponse>(requestState)

                if (headersFirst) {
                    deliverHeaders.complete(Unit)
                    headersStored.await()
                    val beforeCas = storage.latestIndex()
                    assertFalse(agent.compareAndSetSettings(current, update))
                    assertEquals(beforeCas, storage.latestIndex())
                    val fresh = storage.settings[beforeCas]
                    assertTrue(agent.compareAndSetSettings(fresh, fresh.copy(model = updatedModel)))
                } else {
                    assertTrue(agent.compareAndSetSettings(current, update))
                    deliverHeaders.complete(Unit)
                    headersStored.await()
                }
                val finalSettings = storage.settings[storage.latestIndex()]
                assertEquals(updatedModel, finalSettings.model)
                assertEquals("received-turn-state", finalSettings.turnState)
                assertEquals(current.model, requests.single().model)
                assertSame(requestState, agent.state.value)
                assertEquals(current, storage.settings.getExact(settingsIndex))
                running.cancel()
                running.join()
                assertEquals(KodexAgentStateValue.UserMessage, agent.state.value)
                assertEquals(storage.latestIndex(), agent.latestIndex.value)
            }
        }
    }
}

private fun casStorage(): InMemoryKodexAgentStorage =
    InMemoryKodexAgentStorage(KodexAgentSettings(model = OpenAiModelId("initial")))

private fun interceptSettingsWrites(
    backing: MutableKodexAgentStorage,
    beforeWrite: suspend (Int, KodexAgentSettings) -> Unit,
): MutableKodexAgentStorage = object : MutableKodexAgentStorage by backing {
    override val settings = object : MutableIndexVersioned<KodexAgentSettings> by backing.settings {
        override suspend fun set(index: Int, value: KodexAgentSettings) {
            beforeWrite(index, value)
            backing.settings[index] = value
        }
    }
}
