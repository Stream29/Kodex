@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.rpc.client

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.rpcexception.NoMatchException
import io.github.stream29.kodex.utils.rpcexception.SessionNotActive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

val rpcStateInTest by testSuite(testConfig = TestConfig.testScope(isEnabled = false)) {
    test("Get completes before Flow creation and the first update corrects the initial value") {
        runTest {
            val calls = mutableListOf<String>()
            val finishGet = CompletableDeferred<Unit>()
            val sendUpdate = CompletableDeferred<Unit>()
            val initialization = async {
                backgroundScope.rpcStateIn(
                    get = {
                        calls += "get"
                        finishGet.await()
                        calls += "got"
                        1
                    },
                    getFlow = {
                        calls += "flow"
                        flow {
                            calls += "collect"
                            sendUpdate.await()
                            emit(2)
                            awaitCancellation()
                        }
                    },
                )
            }
            runCurrent()
            assertEquals(listOf("get"), calls)
            assertFalse(initialization.isCompleted)
            finishGet.complete(Unit)
            runCurrent()
            val state = initialization.await()
            assertEquals(1, state.value)
            assertEquals(listOf("get", "got", "flow", "collect"), calls)
            sendUpdate.complete(Unit)
            runCurrent()
            assertEquals(2, state.value)
            assertEquals(listOf("get", "got", "flow", "collect"), calls)
        }
    }

    test("nullable initial and streamed values remain actual values") {
        runTest {
            val updates = MutableStateFlow<String?>("current")
            val state = backgroundScope.rpcStateIn(get = { null }, getFlow = { updates })
            assertNull(state.value)
            runCurrent()
            assertEquals("current", state.value)
            updates.value = null
            runCurrent()
            assertNull(state.value)
        }
    }

    for (failure in listOf(
        SessionNotActive(),
        IllegalArgumentException("initial read failed"),
        CancellationException("initial read cancelled"),
    )) {
        test("Get failure ${failure::class.simpleName} never obtains or subscribes to Flow") {
            runTest {
                var reads = 0
                var flowCalls = 0
                val actual = assertFailsWith<Throwable> {
                    backgroundScope.rpcStateIn(
                        get = {
                            reads++
                            throw failure
                        },
                        getFlow = {
                            flowCalls++
                            flow<Int> { error("Unexpected collection") }
                        },
                    )
                }
                assertEquals(failure::class, actual::class)
                assertEquals(failure.message, actual.message)
                runCurrent()
                assertEquals(1, reads)
                assertEquals(0, flowCalls)
            }
        }
    }

    test("immediate Flow creation failure escapes without retrying Get") {
        runTest {
            var reads = 0
            var flowCalls = 0
            assertFailsWith<SessionNotActive> {
                backgroundScope.rpcStateIn(
                    get = { ++reads },
                    getFlow = {
                        flowCalls++
                        throw SessionNotActive()
                    },
                )
            }
            runCurrent()
            assertEquals(1, reads)
            assertEquals(1, flowCalls)
        }
    }

    test("cancelling initialization cancels the Get caller before any Flow is created") {
        runTest {
            val reading = CompletableDeferred<Unit>()
            val readReleased = CompletableDeferred<Unit>()
            var flowCalls = 0
            val initialization = launch {
                backgroundScope.rpcStateIn(
                    get = {
                        try {
                            reading.complete(Unit)
                            awaitCancellation()
                        } finally {
                            readReleased.complete(Unit)
                        }
                    },
                    getFlow = {
                        flowCalls++
                        flow<Int> { error("Unexpected collection") }
                    },
                )
            }
            reading.await()
            initialization.cancelAndJoin()
            readReleased.await()
            assertEquals(0, flowCalls)
        }
    }

    test("observers share one subscription which stays current without observers until owner cancellation") {
        runTest {
            val updates = MutableStateFlow(1)
            val initialized = CompletableDeferred<StateFlow<Int>>()
            val released = CompletableDeferred<Unit>()
            var reads = 0
            var collections = 0
            val owner = launch {
                initialized.complete(rpcStateIn(
                    get = { reads++; 0 },
                    getFlow = {
                        flow {
                            collections++
                            try {
                                emitAll(updates)
                            } finally {
                                released.complete(Unit)
                            }
                        }
                    },
                ))
                awaitCancellation()
            }
            val state = initialized.await()
            runCurrent()
            assertEquals(1, state.value)
            val first = mutableListOf<Int>()
            val second = mutableListOf<Int>()
            val observerA = launch(UnconfinedTestDispatcher(testScheduler)) { state.collect { first += it } }
            val observerB = launch(UnconfinedTestDispatcher(testScheduler)) { state.collect { second += it } }
            updates.value = 2
            runCurrent()
            assertEquals(listOf(1, 2), first)
            assertEquals(listOf(1, 2), second)
            observerA.cancelAndJoin()
            observerB.cancelAndJoin()
            updates.value = 3
            runCurrent()
            assertEquals(3, state.value)
            assertEquals(1, reads)
            assertEquals(1, collections)
            assertFalse(released.isCompleted)
            owner.cancelAndJoin()
            released.await()
            updates.value = 4
            runCurrent()
            assertEquals(3, state.value)
        }
    }

    test("upstream failure belongs to the owner rather than StateFlow observers") {
        runTest {
            supervisorScope {
                val initialized = CompletableDeferred<StateFlow<Int>>()
                val fail = CompletableDeferred<Unit>()
                var collections = 0
                val owner = async {
                    coroutineScope {
                        initialized.complete(rpcStateIn(
                            get = { 0 },
                            getFlow = {
                                flow {
                                    collections++
                                    emit(1)
                                    fail.await()
                                    throw SessionNotActive()
                                }
                            },
                        ))
                        awaitCancellation()
                    }
                }
                val state = initialized.await()
                state.first { it == 1 }
                val observer = backgroundScope.launch { state.collect() }
                runCurrent()
                fail.complete(Unit)
                assertFailsWith<SessionNotActive> { owner.await() }
                assertTrue(observer.isActive)
                assertEquals(1, state.value)
                assertEquals(1, collections)
                observer.cancelAndJoin()
            }
        }
    }

    test("normal upstream completion retains the latest value without restarting or reading again") {
        runTest {
            var reads = 0
            var collections = 0
            val state = backgroundScope.rpcStateIn(
                get = { reads++; 0 },
                getFlow = { flow { collections++; emit(1) } },
            )
            runCurrent()
            assertEquals(1, state.first())
            assertEquals(1, state.first())
            assertEquals(1, reads)
            assertEquals(1, collections)
        }
    }

    test("real RPC state releases its remote subscription with the owner but keeps the connection alive") {
        withProbe { rpc, backend ->
            val initialized = CompletableDeferred<StateFlow<Int>>()
            val owner = launch {
                initialized.complete(rpcStateIn(
                    get = { rpc.echo("0")!!.toInt() },
                    getFlow = rpc::heldValues,
                ))
                awaitCancellation()
            }
            val state = initialized.await()
            assertEquals(1, state.first { it == 1 })
            assertFalse(backend.flowReleased.isCompleted)
            owner.cancelAndJoin()
            backend.flowReleased.await()
            assertEquals("alive", rpc.echo("alive"))
        }
    }

    test("real restored upstream failure reaches the state owner and does not close the connection") {
        withProbe { rpc, backend ->
            supervisorScope {
                val initialized = CompletableDeferred<StateFlow<Int>>()
                val owner = async {
                    coroutineScope {
                        initialized.complete(rpcStateIn(
                            get = { rpc.echo("0")!!.toInt() },
                            getFlow = { rpc.failingValues("noMatch", failAtBinding = false) },
                        ))
                        awaitCancellation()
                    }
                }
                val state = initialized.await()
                state.first { it == 1 }
                backend.firstValueReceived.complete(Unit)
                assertFailsWith<NoMatchException> { owner.await() }
                backend.flowReleased.await()
                assertEquals("alive", rpc.echo("alive"))
            }
        }
    }
}
