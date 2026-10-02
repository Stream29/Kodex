package io.github.stream29.kodex.cli.agent

import de.infix.testBalloon.framework.core.testSuite
import de.infix.testBalloon.framework.core.TestCompartment
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanCompactionPoint
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAgentMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableDeveloperMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.contract.IndexVersioned
import io.github.stream29.kodex.app.agent.contract.HistoryIndexDependencies
import io.github.stream29.kodex.app.agent.contract.HistoryIndexReadHandle
import io.github.stream29.kodex.app.agent.contract.HistoryIndexReadState
import io.github.stream29.kodex.app.agent.contract.HistoryIndexViewModel
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.PlanItemArg
import io.github.stream29.kodex.openai.StepStatus
import io.github.stream29.kodex.openai.UpdatePlanArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputAnswer
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.utils.rpcexception.CacheNonceMismatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.*
import kotlin.time.Instant

val historyIndexComponentTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("local sparse scan skips only initial compaction and appends incrementally oldest first") {
        val deps = IndexPorts(production = false)
        deps.timeline.values.putAll(mapOf(0 to CleanCompactionPoint, 2 to message("question\nwith   spaces")))
        deps.latestIndex.value = 3
        withIndex(deps) { vm ->
            val initial = vm.awaitWindow { it.indexes == listOf(2) }
            assertEquals(0L, initial.generation)
            assertEquals("question with spaces", vm.load(initial.generation, 2).summary)
            deps.timeline.values[5] = CleanCompactionPoint
            deps.latestIndex.value = 5
            val append = vm.awaitWindow { it.indexes == listOf(2, 5) }
            assertEquals(initial.generation, append.generation)
            assertEquals(listOf(0..3, 4..5), deps.timeline.ranges.value)
            deps.timeline.values.remove(5)
            deps.latestIndex.value = 3
            val reverted = vm.awaitWindow { it.generation == 1L }
            assertEquals(listOf(2), reverted.indexes)
            assertFalse(vm.contains(initial.generation, 2))
            assertFailsWith<HistoryIndexLoadException> { vm.load(initial.generation, 2) }
        }
    }

    test("external equal boundary rescans local fallback and production without inventing nonce") {
        for (production in listOf(false, true)) {
            val deps = IndexPorts(production)
            deps.timeline.values[2] = message("before")
            deps.latestIndex.value = 2
            withIndex(deps) { vm ->
                val initial = vm.awaitInitial(production)
                val row = vm.acquireRow(initial.generation, 2)
                row.awaitReady()
                deps.externalWrite.value = true
                deps.timeline.values[2] = message("after")
                deps.externalWrite.value = false
                val refreshed = vm.awaitWindow { it.revision > initial.revision }
                assertEquals(if (production) 11L else initial.generation + 1, refreshed.generation)
                assertEquals(HistoryIndexReadState.Closed, row.state.value)
                assertEquals("after", vm.load(refreshed.generation, 2).summary)
                val next = vm.acquireRow(refreshed.generation, 2)
                assertEquals("after", next.awaitReady().summary)
                next.release()
            }
        }
    }

    test("nonce replacement keeps exact production nonce even when it decreases and indexes match") {
        val deps = IndexPorts()
        deps.timeline.values[2] = message("before")
        deps.latestIndex.value = 2
        withIndex(deps) { vm ->
            val old = vm.awaitInitial(true)
            val detail = vm.acquireDetail(old.generation, 2)
            detail.awaitReady()
            deps.timeline.values[2] = message("replaced")
            deps.cacheNonce!!.value = -75
            val next = vm.awaitWindow { it.generation == -75L }
            assertEquals(listOf(2), next.indexes)
            assertEquals(HistoryIndexReadState.Closed, detail.state.value)
            assertFalse(vm.checkOut(old.generation, 2))
            assertTrue(vm.checkOut(next.generation, 2))
            assertEquals(listOf(2), deps.scrolled)
        }
    }

    test("delayed scan cannot publish results captured under an old nonce") {
        val deps = IndexPorts()
        deps.timeline.values[2] = message("base")
        deps.latestIndex.value = 2
        withIndex(deps) { vm ->
            vm.awaitInitial(true)
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            deps.timeline.rangeRead = { range ->
                if (range.first == 3) {
                    started.complete(Unit)
                    finish.await()
                    throw CacheNonceMismatch()
                }
                deps.timeline.values.keys.filter { it in range }.sorted()
            }
            deps.latestIndex.value = 5
            withTimeout(5_000) { started.await() }
            deps.timeline.values.clear()
            deps.timeline.values[4] = message("replacement")
            deps.cacheNonce!!.value = 99
            finish.complete(Unit)
            val replaced = vm.awaitWindow { it.generation == 99L }
            assertEquals(listOf(4), replaced.indexes)
            assertFalse(vm.contains(11, 2))
        }
    }

    test("independent row and detail handles suppress noncooperative late results after release") {
        for (detail in listOf(false, true)) {
            val deps = IndexPorts(production = false)
            deps.timeline.values[2] = message("late")
            deps.latestIndex.value = 2
            withIndex(deps) { vm ->
                val window = vm.awaitInitial(false)
                val started = CompletableDeferred<Unit>()
                val finish = CompletableDeferred<Unit>()
                val returned = CompletableDeferred<Unit>()
                deps.timeline.exactRead = { index ->
                    withContext(NonCancellable) {
                        started.complete(Unit)
                        finish.await()
                        returned.complete(Unit)
                        deps.timeline.values[index]
                    }
                }
                try {
                    val handle: HistoryIndexReadHandle<*> = if (detail) vm.acquireDetail(window.generation, 2)
                        else vm.acquireRow(window.generation, 2)
                    withTimeout(5_000) { started.await() }
                    handle.release()
                    assertEquals(HistoryIndexReadState.Closed, handle.state.value)
                    finish.complete(Unit)
                    withTimeout(5_000) { returned.await() }
                    assertEquals(HistoryIndexReadState.Closed, handle.state.value)
                    assertTrue(vm.contains(window.generation, 2))
                } finally {
                    finish.complete(Unit)
                }
            }
        }
    }

    test("two sidebars own separate reads while sharing one sparse window") {
        val deps = IndexPorts(production = false)
        deps.timeline.values[2] = message("shared")
        deps.latestIndex.value = 2
        withIndex(deps) { vm ->
            val window = vm.awaitInitial(false)
            val left = vm.acquireRow(window.generation, 2)
            val right = vm.acquireRow(window.generation, 2)
            assertNotSame(left, right)
            left.awaitReady()
            right.awaitReady()
            left.release()
            assertEquals("shared", right.awaitReady().summary)
            assertEquals(HistoryIndexReadState.Closed, left.state.value)
            vm.close()
            assertEquals(HistoryIndexReadState.Closed, right.state.value)
            assertFalse(vm.isActive)
            assertFalse(vm.checkOut(window.generation, 2))
            assertEquals(HistoryIndexReadState.Closed, vm.acquireDetail(window.generation, 2).state.value)
        }
    }

    test("nonce invalidation suppresses late row detail and timestamp results at the same index") {
        for (kind in listOf("row", "detail", "timestamp")) {
            val deps = IndexPorts()
            deps.timeline.values[2] = message("before")
            deps.latestIndex.value = 2
            withIndex(deps) { vm ->
                val generation = vm.awaitInitial(true).generation
                val started = CompletableDeferred<Unit>()
                val finish = CompletableDeferred<Unit>()
                val returned = CompletableDeferred<Unit>()
                if (kind == "timestamp") {
                    deps.timestamp.exactRead = {
                        withContext(NonCancellable) {
                            started.complete(Unit)
                            finish.await()
                            returned.complete(Unit)
                            Instant.parse("2026-10-03T01:02:03Z")
                        }
                    }
                } else {
                    deps.timeline.exactRead = {
                        withContext(NonCancellable) {
                            started.complete(Unit)
                            finish.await()
                            returned.complete(Unit)
                            message("late old result")
                        }
                    }
                }
                try {
                    val handle: HistoryIndexReadHandle<*> = when (kind) {
                        "row" -> vm.acquireRow(generation, 2)
                        "detail" -> vm.acquireDetail(generation, 2)
                        else -> vm.acquireTimestamp(generation, 2)
                    }
                    withTimeout(5_000) { started.await() }
                    deps.cacheNonce!!.value = 22
                    vm.awaitWindow { it.generation == 22L }
                    assertEquals(HistoryIndexReadState.Closed, handle.state.value)
                    finish.complete(Unit)
                    withTimeout(5_000) { returned.await() }
                    assertEquals(HistoryIndexReadState.Closed, handle.state.value)
                    assertFalse(vm.contains(generation, 2))
                } finally {
                    finish.complete(Unit)
                }
            }
        }
    }

    test("ordinary read failure is safe Failed but cancellation closes without Failed") {
        val deps = IndexPorts(production = false)
        deps.timeline.values[2] = message("base")
        deps.latestIndex.value = 2
        withIndex(deps) { vm ->
            val generation = vm.awaitInitial(false).generation
            deps.timeline.exactRead = { throw IllegalStateException("raw secret diagnostic") }
            assertFailsWith<HistoryIndexLoadException> { vm.load(generation, 2) }
            val failed = vm.acquireRow(generation, 2)
            assertEquals(HistoryIndexReadState.Failed, withTimeout(5_000) {
                failed.state.first { it != HistoryIndexReadState.Loading }
            })
            deps.timeline.exactRead = { throw CancellationException("cancel") }
            val cancelled = vm.acquireDetail(generation, 2)
            assertEquals(HistoryIndexReadState.Closed, withTimeout(5_000) {
                cancelled.state.first { it != HistoryIndexReadState.Loading }
            })
            assertTrue(vm.isActive)
            failed.release()
        }
    }

    test("timestamp reads are exact Message only and late menu timestamp cannot revive release") {
        val deps = IndexPorts(production = false)
        val time = Instant.parse("2026-10-03T01:02:03Z")
        deps.timeline.values.putAll(mapOf(2 to message("message"), 5 to CleanCompactionPoint))
        deps.timestamp.values.putAll(mapOf(1 to time, 5 to time))
        deps.latestIndex.value = 5
        withIndex(deps) { vm ->
            val generation = vm.awaitWindow { it.indexes == listOf(2, 5) }.generation
            assertNull(vm.readMessageTimestamp(generation, 2)) // No preceding timestamp fallback.
            assertNull(vm.readMessageTimestamp(generation, 5)) // No non-Message timestamp.
            assertEquals(listOf(2), deps.timestamp.exacts.value)
            deps.timestamp.values[2] = time
            assertEquals(time, vm.readMessageTimestamp(generation, 2))
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            deps.timestamp.exactRead = {
                withContext(NonCancellable) {
                    started.complete(Unit)
                    finish.await()
                    time
                }
            }
            try {
                val menu = vm.acquireTimestamp(generation, 2)
                withTimeout(5_000) { started.await() }
                menu.release()
                finish.complete(Unit)
                assertEquals(HistoryIndexReadState.Closed, menu.state.value)
            } finally {
                finish.complete(Unit)
            }
        }
    }

    test("raw caller cancellation and owner cancellation do not publish late reads") {
        val deps = IndexPorts(production = false)
        deps.timeline.values[2] = message("base")
        deps.latestIndex.value = 2
        withIndex(deps) { vm ->
            val generation = vm.awaitInitial(false).generation
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            deps.timeline.exactRead = {
                withContext(NonCancellable) { started.complete(Unit); finish.await(); message("late") }
            }
            val callerOwner = SupervisorJob()
            val caller = CoroutineScope(Dispatchers.Default + callerOwner).async { vm.load(generation, 2) }
            try {
                withTimeout(5_000) { started.await() }
                caller.cancel()
                finish.complete(Unit)
                assertFailsWith<CancellationException> { caller.await() }
            } finally {
                finish.complete(Unit)
                caller.cancelAndJoin()
                callerOwner.cancelAndJoin()
            }
        }
        val owner = SupervisorJob()
        val vm = createHistoryIndexViewModel(deps, CoroutineScope(Dispatchers.Unconfined + owner))
        try {
            deps.timeline.exactRead = null
            val generation = vm.awaitInitial(false).generation
            val row = vm.acquireRow(generation, 2)
            row.awaitReady()
            owner.cancelAndJoin()
            assertFalse(vm.isActive)
            assertEquals(HistoryIndexReadState.Closed, row.state.value)
        } finally { vm.close(); owner.cancelAndJoin() }
    }

    test("ordinary scan failure reaches owner handler rather than publishing success") {
        val deps = IndexPorts(production = false)
        val failure = IllegalStateException("scan failed")
        deps.latestIndex.value = 3
        deps.timeline.rangeRead = { throw failure }
        val reported = CompletableDeferred<Throwable>()
        val owner = SupervisorJob()
        val vm = createHistoryIndexViewModel(deps, CoroutineScope(
            Dispatchers.Unconfined + owner + CoroutineExceptionHandler { _, error -> reported.complete(error) },
        ))
        try {
            val reportedFailure = withTimeout(5_000) { reported.await() }
            assertSame(failure, generateSequence(reportedFailure) { it.cause }.last())
            assertEquals(emptyList(), vm.window.value.indexes)
            assertFalse(vm.contains(vm.window.value.generation, 3))
        } finally { vm.close(); owner.cancelAndJoin() }
    }

    test("detail mapping keeps image encrypted placeholders complete plans and hidden secrets") {
        val deps = IndexPorts(production = false)
        deps.timeline.values.putAll(mapOf(
            1 to StableDeveloperMessage(listOf(ContentItem.InputText("developer"), ContentItem.InputImage("image"))),
            2 to StableAgentMessage("one", "two", listOf(
                AgentMessageInputContent.InputText("private"), AgentMessageInputContent.EncryptedContent("cipher"),
            )),
            3 to StableRequestUserInputToolEvent(
                callId = "request", arguments = RequestUserInputArgs(listOf(RequestUserInputQuestion(
                    "secret", "Credential", "Enter token", isSecret = true,
                ))),
                result = StableRequestUserInputResult.Answered(RequestUserInputResponse(mapOf(
                    "secret" to RequestUserInputAnswer(listOf("never-display-secret")),
                ))),
            ),
            4 to StablePlanUpdate(callId = "plan", arguments = UpdatePlanArgs("Updated", listOf(
                PlanItemArg("done", StepStatus.Completed),
                PlanItemArg("current", StepStatus.InProgress),
                PlanItemArg("later", StepStatus.Pending),
            ))),
            5 to message(" \n "),
        ))
        deps.latestIndex.value = 5
        withIndex(deps) { vm ->
            val generation = vm.awaitWindow { it.indexes == (1..5).toList() }.generation
            assertEquals("developer[image]", vm.load(generation, 1).summary)
            assertEquals("Author: one\nRecipient: two\n\nprivate[encrypted content]", vm.loadDetail(generation, 2).content)
            val secret = vm.loadDetail(generation, 3).content
            assertTrue("[hidden]" in secret)
            assertFalse("never-display-secret" in secret)
            assertEquals("Updated\n\n[x] done\n[>] current\n[ ] later", vm.loadDetail(generation, 4).content)
            assertEquals("[empty]", vm.load(generation, 5).summary)
        }
    }
}

private fun message(text: String): CleanIndexEntry = StableUserMessage(listOf(ContentItem.InputText(text)))

private class IndexPorts(production: Boolean = true) : HistoryIndexDependencies {
    override val timeline = FakeTimeline<CleanIndexEntry>()
    override val timestamp = FakeTimeline<Instant>()
    override val latestIndex = MutableStateFlow(-1)
    override val cacheNonce = if (production) MutableStateFlow(11L) else null
    override val externalWrite = MutableStateFlow(false)
    val scrolled = mutableListOf<Int>()
    override fun requestScrollToStorageIndex(index: Int) { scrolled += index }
}

private class FakeTimeline<T> : IndexVersioned<T> {
    val values = mutableMapOf<Int, T>()
    val ranges = MutableStateFlow<List<IntRange>>(emptyList())
    val exacts = MutableStateFlow<List<Int>>(emptyList())
    var exactRead: (suspend (Int) -> T?)? = null
    var rangeRead: (suspend (IntRange) -> List<Int>)? = null
    override suspend fun latestIndex() = values.keys.maxOrNull() ?: -1
    override suspend fun get(index: Int): T = values.getValue(requireNotNull(floorToIndex(index)))
    override suspend fun getExact(index: Int): T? {
        exacts.update { it + index }
        val read = exactRead
        return if (read != null) read(index) else values[index]
    }
    override suspend fun floorToIndex(index: Int) = values.keys.filter { it <= index }.maxOrNull()
    override suspend fun ceilToIndex(index: Int) = values.keys.filter { it >= index }.minOrNull()
    override suspend fun indexesIn(range: IntRange): List<Int> {
        ranges.update { it + listOf(range) }
        return rangeRead?.invoke(range) ?: values.keys.filter { it in range }.sorted()
    }
    override suspend fun valuesIn(range: IntRange) = indexesIn(range).map { it to values.getValue(it) }
}

private suspend fun withIndex(deps: IndexPorts, block: suspend (HistoryIndexViewModel) -> Unit) {
    val owner = SupervisorJob()
    val vm = historyIndexViewModelFactory.create(deps, CoroutineScope(Dispatchers.Unconfined + owner))
    try { block(vm) } finally { vm.close(); withTimeout(5_000) { owner.cancelAndJoin() } }
}

private suspend fun HistoryIndexViewModel.awaitWindow(
    predicate: (io.github.stream29.kodex.app.agent.contract.HistoryIndexWindow) -> Boolean,
) = withTimeout(5_000) { window.first(predicate) }

private suspend fun HistoryIndexViewModel.awaitInitial(production: Boolean) =
    awaitWindow { it.indexes == listOf(2) && (!production || it.revision > 0) }

private suspend fun <T> HistoryIndexReadHandle<T>.awaitReady(): T = withTimeout(5_000) {
    (state.first { it is HistoryIndexReadState.Ready } as HistoryIndexReadState.Ready).value
}
