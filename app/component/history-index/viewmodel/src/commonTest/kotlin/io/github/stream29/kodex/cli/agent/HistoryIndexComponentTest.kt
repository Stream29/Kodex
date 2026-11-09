package io.github.stream29.kodex.cli.agent

import de.infix.testBalloon.framework.core.testSuite
import de.infix.testBalloon.framework.core.TestCompartment
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexedAgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAgentMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableDeveloperMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableIndexEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.app.agent.contract.HistoryIndexDependencies
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import kotlin.test.*
import kotlin.time.Instant

val historyIndexComponentTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("local sparse scan appends incrementally oldest first and invalidates on revert") {
        val deps = IndexPorts(production = false)
        deps.timeline.values[2] = message("question\nwith   spaces")
        deps.latestIndex.value = 3
        withIndex(deps) { vm ->
            val initial = vm.awaitWindow { it.indexes == listOf(2) }
            assertEquals(0L, initial.generation)
            assertEquals("question with spaces", assertNotNull(initial.entryAtOrNull(2)).summary)
            deps.timeline.values[5] = StableContextCompaction(encryptedContent = "enc")
            deps.latestIndex.value = 5
            val append = vm.awaitWindow { it.indexes == listOf(2, 5) }
            assertEquals(initial.generation, append.generation)
            assertEquals("Context compacted", assertNotNull(append.entryAtOrNull(5)).summary)
            assertEquals(listOf(0..3, 4..5), deps.timeline.ranges.value)
            deps.timeline.values.remove(5)
            deps.latestIndex.value = 3
            val reverted = vm.awaitWindow { it.generation == 1L }
            assertEquals(listOf(2), reverted.indexes)
            assertFalse(vm.contains(initial.generation, 2))
        }
    }

    test("external equal boundary rescans local fallback and production without inventing nonce") {
        for (production in listOf(false, true)) {
            val deps = IndexPorts(production)
            deps.timeline.values[2] = message("before")
            deps.latestIndex.value = 2
            withIndex(deps) { vm ->
                val initial = vm.awaitInitial(production)
                assertEquals("before", assertNotNull(initial.entryAtOrNull(2)).summary)
                deps.externalWrite.value = true
                deps.timeline.values[2] = message("after")
                deps.externalWrite.value = false
                val refreshed = vm.awaitWindow { it.revision > initial.revision }
                assertEquals(if (production) 11L else initial.generation + 1, refreshed.generation)
                assertEquals("after", assertNotNull(refreshed.entryAtOrNull(2)).summary)
            }
        }
    }

    test("nonce replacement keeps exact production nonce even when it decreases and indexes match") {
        val deps = IndexPorts()
        deps.timeline.values[2] = message("before")
        deps.latestIndex.value = 2
        withIndex(deps) { vm ->
            val old = vm.awaitInitial(true)
            deps.timeline.values[2] = message("replaced")
            deps.cacheNonce!!.value = -75
            val next = vm.awaitWindow { it.generation == -75L }
            assertEquals(listOf(2), next.indexes)
            assertEquals("replaced", assertNotNull(next.entryAtOrNull(2)).summary)
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

    test("closing view model deactivates checkOut") {
        val deps = IndexPorts(production = false)
        deps.timeline.values[2] = message("shared")
        deps.latestIndex.value = 2
        withIndex(deps) { vm ->
            val window = vm.awaitInitial(false)
            assertEquals("shared", assertNotNull(window.entryAtOrNull(2)).summary)
            vm.close()
            assertFalse(vm.isActive)
            assertFalse(vm.checkOut(window.generation, 2))
        }
    }

    test("timestamp is populated on Message entries only") {
        val deps = IndexPorts(production = false)
        val time = Instant.parse("2026-10-03T01:02:03Z")
        deps.timeline.values.putAll(mapOf(2 to message("message"), 5 to StableContextCompaction(encryptedContent = "enc")))
        deps.timestamp.values.putAll(mapOf(2 to time, 5 to time))
        deps.latestIndex.value = 5
        withIndex(deps) { vm ->
            val window = vm.awaitWindow { it.indexes == listOf(2, 5) }
            assertEquals(time, assertNotNull(window.entryAtOrNull(2)).messageTimestamp)
            assertNull(assertNotNull(window.entryAtOrNull(5)).messageTimestamp)
        }
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
            val window = vm.awaitWindow { it.indexes == (1..5).toList() }
            assertEquals("developer[image]", assertNotNull(window.entryAtOrNull(1)).summary)
            assertEquals("Author: one\nRecipient: two\n\nprivate[encrypted content]", assertNotNull(window.entryAtOrNull(2)).detail.content)
            val secret = assertNotNull(window.entryAtOrNull(3)).detail.content
            assertTrue("[hidden]" in secret)
            assertFalse("never-display-secret" in secret)
            val plan = assertNotNull(window.entryAtOrNull(4)).detail
            assertEquals("Updated\n\n[x] done\n[>] current\n[ ] later", plan.content)
            assertEquals(listOf("done", "current", "later"), assertNotNull(plan.plan).arguments.plan.map { it.step })
            assertEquals("[empty]", assertNotNull(window.entryAtOrNull(5)).summary)
        }
    }

    test("initial scan loads only latest page and lazily prepends older pages on top row visibility") {
        val deps = IndexPorts(production = false)
        for (i in 1..150) {
            deps.timeline.values[i] = message("msg $i")
        }
        deps.latestIndex.value = 150
        withIndex(deps) { vm ->
            val firstPage = vm.awaitWindow { it.indexes == (87..150).toList() }
            assertEquals(listOf(0..150), deps.timeline.ranges.value)
            assertEquals("msg 90", assertNotNull(firstPage.entryAtOrNull(90)).summary)
            vm.onRowVisible(firstPage.generation, 90)
            val secondPage = vm.awaitWindow { it.indexes == (23..150).toList() }
            assertEquals(firstPage.generation, secondPage.generation)
            assertEquals(firstPage.revision, secondPage.revision)
            assertEquals("msg 25", assertNotNull(secondPage.entryAtOrNull(25)).summary)
            vm.onRowVisible(secondPage.generation, 25)
            val fullWindow = vm.awaitWindow { it.indexes == (1..150).toList() }
            assertEquals(listOf(0..150, 0..86, 0..22), deps.timeline.ranges.value)
            assertEquals("msg 1", assertNotNull(fullWindow.entryAtOrNull(1)).summary)
        }
    }
}

private fun message(text: String): CleanIndexEntry = StableUserMessage(listOf(ContentItem.InputText(text)))

private class IndexPorts(production: Boolean = true) : HistoryIndexDependencies {
    val timeline = FakeTimeline<CleanIndexEntry>()
    val timestamp = FakeTimeline<Instant>()
    override val storage: KodexAgentStorage = object : KodexAgentStorage {
        override val uri: String = "kodex://test"
        override suspend fun latestIndex(): Int = latestIndex.value
        override suspend fun countCommitsIn(range: IntRange, types: Set<AgentCommitType>): Int = 0
        override suspend fun getFirstCommitsIn(
            range: IntRange,
            types: Set<AgentCommitType>,
            limit: Int,
        ): List<IndexedAgentCommit> = emptyList()
        override suspend fun getLastCommitsIn(
            range: IntRange,
            types: Set<AgentCommitType>,
            limit: Int,
        ): List<IndexedAgentCommit> =
            when (types) {
                AgentCommitType.IndexTypes -> timeline.indexesIn(range).mapNotNull { idx ->
                    timeline.values[idx]?.let { entry ->
                        val createdAt = if (entry is StableIndexEvent.Steerable) {
                            timestamp.values[idx] ?: Instant.DISTANT_PAST
                        } else {
                            Instant.DISTANT_PAST
                        }
                        IndexedAgentCommit(idx, entry, createdAt)
                    }
                }.takeLast(limit)
                else -> emptyList()
            }
    }
    override val latestIndex = MutableStateFlow(-1)
    override val cacheNonce = if (production) MutableStateFlow(11L) else null
    override val externalWrite = MutableStateFlow(false)
    val scrolled = mutableListOf<Int>()
    override fun requestScrollToStorageIndex(index: Int) { scrolled += index }
}

private class FakeTimeline<T> {
    val values = mutableMapOf<Int, T>()
    val ranges = MutableStateFlow<List<IntRange>>(emptyList())
    var rangeRead: (suspend (IntRange) -> List<Int>)? = null
    suspend fun indexesIn(range: IntRange): List<Int> {
        ranges.update { it + listOf(range) }
        return rangeRead?.invoke(range) ?: values.keys.filter { it in range }.sorted()
    }
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
