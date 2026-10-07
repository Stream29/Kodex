@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.cli.history

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.contract.IndexVersioned
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.revert
import io.github.stream29.kodex.app.history.contract.AgentHistoryLoadState
import io.github.stream29.kodex.app.history.contract.AgentHistoryViewModel
import io.github.stream29.kodex.app.history.contract.HistoryItemWindow
import io.github.stream29.kodex.app.history.contract.HistoryScrollEffect
import io.github.stream29.kodex.app.history.contract.HistoryScrollTarget
import io.github.stream29.kodex.app.history.contract.item.HistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemViewModel
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemState
import io.github.stream29.kodex.app.history.contract.item.WorkGroupHistoryItemViewModel
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

val historyViewportAndScrollEffectTest by testSuite {
    for (latest in listOf(false, true)) {
        test("blocked original structural read plus 256 navigations retains newest ${if (latest) "latest" else "exact item"}") {
            coroutineScope {
                val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
                val runtime = repository.open(repository.create()).runtime
                runtime.modify { storage ->
                    for (index in 1..20) storage.index[index] = navigationMessage("$index")
                }
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val armed = MutableStateFlow(false)
                val original = runtime.storage
                val storage = object : KodexAgentStorage by original {
                    override val index = object : IndexVersioned<CleanIndexEntry> by original.index {
                        override suspend fun getExact(index: Int): CleanIndexEntry? {
                            if (index == 7 && armed.compareAndSet(true, false)) {
                                entered.complete(Unit)
                                release.await()
                            }
                            return original.index.getExact(index)
                        }
                    }
                }
                val model = createAgentHistoryViewModel(
                    AgentHistorySource(storage, runtime.latestIndex, runtime.state),
                    supervisorChildScope(), MutableStateFlow(false),
                )
                try {
                    model.awaitNavigationReady()
                    model.requestScrollToStorageIndex(5)
                    val stale = model.awaitEffect()
                    armed.value = true
                    model.requestScrollToStorageIndex(7)
                    withContext(Dispatchers.Default) { withTimeout(5.seconds) { entered.await() } }
                    // No loop can consume while the real read is blocked. Exceeds Channel.BUFFERED.
                    repeat(256) { number ->
                        if (number % 2 == 0) model.requestScrollToLatest()
                        else model.requestScrollToStorageIndex(3)
                    }
                    if (latest) model.requestScrollToLatest() else model.requestScrollToStorageIndex(3)
                    assertNull(model.pendingScrollEffect.value)
                    release.complete(Unit)
                    val newest = model.awaitEffect()
                    if (latest) assertEquals(HistoryScrollTarget.Latest, newest.target)
                    else assertEquals(3, assertIs<MessageHistoryItemViewModel>(
                        assertIs<HistoryScrollTarget.Item>(newest.target).item,
                    ).index)
                    model.acknowledgeScrollEffect(stale)
                    assertSame(newest, model.pendingScrollEffect.value)
                    model.awaitNavigationReady()
                    assertSame(newest, model.pendingScrollEffect.value, "Late mount must still receive newest intent.")
                } finally {
                    release.complete(Unit)
                    model.close()
                    repository.cancelAndJoin()
                }
            }
        }
    }
    test("saturated navigation followed by nonce withdrawal preserves new Latest after queued invalidation drains") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                for (index in 1..20) storage.index[index] = navigationMessage("$index")
                // UpdateLatestTurn's duration is a FIFO barrier after Invalidate, not a transient effect probe.
                storage.timestamp[1] = Clock.System.now()
            }
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val armed = MutableStateFlow(false)
            val original = runtime.storage
            val storage = object : KodexAgentStorage by original {
                override val index = object : IndexVersioned<CleanIndexEntry> by original.index {
                    override suspend fun getExact(index: Int): CleanIndexEntry? {
                        if (index == 7 && armed.compareAndSet(true, false)) {
                            entered.complete(Unit)
                            release.await()
                        }
                        return original.index.getExact(index)
                    }
                }
            }
            val nonce = MutableStateFlow(40L)
            val running = MutableStateFlow(false)
            val scheduler = TestCoroutineScheduler()
            val owner = Job(coroutineContext[Job])
            val model = createAgentHistoryViewModel(
                AgentHistorySource(storage, runtime.latestIndex, runtime.state, nonce),
                CoroutineScope(coroutineContext + owner + StandardTestDispatcher(scheduler) + scheduler), running,
            )
            suspend fun settleUntil(condition: () -> Boolean) = withContext(Dispatchers.Default) {
                withTimeout(5.seconds) {
                    do { scheduler.runCurrent(); yield() } while (!condition())
                    scheduler.runCurrent()
                }
            }
            try {
                settleUntil { model.loadState.value == AgentHistoryLoadState.Ready }
                model.requestScrollToLatest()
                settleUntil { model.pendingScrollEffect.value != null }
                val stale = model.pendingScrollEffect.value!!
                val old = model.historyItems.value
                val oldChild = assertIs<MessageHistoryItemViewModel>(old.peek(0))
                val oldLoading = assertIs<MessageHistoryItemState.Loading>(oldChild.state.value).loadingJob
                armed.value = true
                model.requestScrollToStorageIndex(7)
                settleUntil { entered.isCompleted }
                repeat(256) { number ->
                    if (number % 2 == 0) model.requestScrollToLatest()
                    else model.requestScrollToStorageIndex(3)
                }
                nonce.value = 41
                // The real nonce collector withdraws old navigation and suspends sending
                // Invalidate into the full channel while getExact(7) still holds the read gate.
                scheduler.runCurrent()
                assertNull(model.pendingScrollEffect.value)
                model.requestScrollToLatest()
                running.value = true
                scheduler.runCurrent() // Queue the turn barrier behind the suspended Invalidate sender.
                release.complete(Unit)
                settleUntil { model.activeTurnDuration.value != null }
                // Every saturated wake and Invalidate preceding this FIFO barrier has been consumed.
                running.value = false
                settleUntil { model.activeTurnDuration.value == null }
                assertEquals(AgentHistoryLoadState.Ready, model.loadState.value)
                val finalWindow = model.historyItems.value
                assertEquals(41L, finalWindow.generation)
                val finalChild = assertIs<MessageHistoryItemViewModel>(finalWindow.peek(0))
                assertEquals(20, finalChild.index)
                val finalEffect = assertIs<HistoryScrollEffect>(model.pendingScrollEffect.value)
                assertEquals(41L, finalEffect.generation)
                assertEquals(HistoryScrollTarget.Latest, finalEffect.target)
                assertTrue(model.followsLatest.value)
                assertFalse(model.contains(old.generation, 20))
                assertTrue(oldLoading.isCancelled, "Old-generation lazy payload was released, not reused.")
                old[0] // Late access must not restart the released child.
                assertSame(oldLoading, assertIs<MessageHistoryItemState.Loading>(oldChild.state.value).loadingJob)
                assertFailsWith<IllegalStateException> { oldChild.readTimestamp() }
                for (index in 0 until finalWindow.size) assertNotSame(oldChild, finalWindow.peek(index))
                finalWindow[0]
                settleUntil { finalChild.state.value is MessageHistoryItemState.Ready }
                assertIs<MessageHistoryItemState.Ready>(finalChild.state.value)
                finalChild.readTimestamp() // New child has the current load context, unlike the released one.
                model.acknowledgeScrollEffect(stale)
                assertSame(finalEffect, model.pendingScrollEffect.value)
                model.acknowledgeScrollEffect(finalEffect)
                assertNull(model.pendingScrollEffect.value)
            } finally {
                release.complete(Unit)
                model.close()
                owner.cancel()
                settleUntil { owner.isCompleted }
                owner.join()
                repository.cancelAndJoin()
            }
        }
    }
    for (rewrite in listOf(false, true)) {
        for (appendAfterCompletion in listOf(false, true)) {
            test("delayed ${if (rewrite) "rewrite" else "revert"} completion retains new nonce Latest${if (appendAfterCompletion) " beyond captured end" else ""}") {
                coroutineScope {
                    val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
                    val runtime = repository.open(repository.create()).runtime
                    runtime.modify { storage ->
                        for (index in 1..20) storage.index[index] = navigationMessage("$index")
                        storage.timestamp[1] = Clock.System.now()
                    }
                    val entered = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val armed = MutableStateFlow(false)
                    val original = runtime.storage
                    val storage = object : KodexAgentStorage by original {
                        override val index = object : IndexVersioned<CleanIndexEntry> by original.index {
                            override suspend fun getExact(index: Int): CleanIndexEntry? {
                                if (index == 7 && armed.compareAndSet(true, false)) {
                                    entered.complete(Unit)
                                    release.await()
                                }
                                return original.index.getExact(index)
                            }
                        }
                    }
                    // Control delivery, not History behavior: real storage/runtime mutation below,
                    // actual VM nonce/state collectors and bounded command channel throughout.
                    val latest = MutableStateFlow(20)
                    val state = MutableStateFlow<KodexAgentStateValue>(KodexAgentStateValue.UserMessage)
                    val nonce = MutableStateFlow(40L)
                    val running = MutableStateFlow(false)
                    val scheduler = TestCoroutineScheduler()
                    val owner = Job(coroutineContext[Job])
                    val ownerScope = CoroutineScope(
                        coroutineContext + owner + StandardTestDispatcher(scheduler) + scheduler,
                    )
                    val model = createAgentHistoryViewModel(
                        AgentHistorySource(storage, latest, state, nonce), ownerScope, running,
                    )
                    suspend fun settleUntil(condition: () -> Boolean) = withContext(Dispatchers.Default) {
                        withTimeout(5.seconds) {
                            do { scheduler.runCurrent(); yield() } while (!condition())
                            scheduler.runCurrent()
                        }
                    }
                    try {
                        settleUntil { model.loadState.value == AgentHistoryLoadState.Ready }
                        model.requestScrollToLatest()
                        settleUntil { model.pendingScrollEffect.value != null }
                        val stale = model.pendingScrollEffect.value!!
                        val old = model.historyItems.value
                        val oldChild = assertIs<MessageHistoryItemViewModel>(old.peek(0))
                        val oldLoading = assertIs<MessageHistoryItemState.Loading>(oldChild.state.value).loadingJob
                        armed.value = true
                        model.requestScrollToStorageIndex(7)
                        settleUntil { entered.isCompleted }
                        repeat(256) { number ->
                            if (number % 2 == 0) model.requestScrollToLatest()
                            else model.requestScrollToStorageIndex(3)
                        }
                        state.value = KodexAgentStateValue.ExternalWrite
                        scheduler.runCurrent() // Capture the operation's start cursor, before mutation.
                        val endIndex = if (rewrite) 20 else 3
                        runtime.modify {
                            it.revert(endIndex)
                            it.index[endIndex] = navigationMessage("replacement")
                        }
                        assertEquals(endIndex, runtime.latestIndex.value)
                        latest.value = endIndex
                        nonce.value = 41
                        scheduler.runCurrent() // Refresh/Invalidate senders wait behind saturated wakes.
                        state.value = KodexAgentStateValue.UserMessage
                        scheduler.runCurrent() // Actual state collector queues ExternalWriteFinished.
                        if (appendAfterCompletion) {
                            runtime.modify { it.index[endIndex + 1] = navigationMessage("appended") }
                            latest.value = endIndex + 1
                            scheduler.runCurrent() // Completion's end is now stale, nonce is still 41.
                        }
                        assertNull(model.pendingScrollEffect.value)
                        model.requestScrollToLatest()
                        val published = CompletableDeferred<Pair<HistoryScrollEffect, HistoryItemWindow>>()
                        ownerScope.launch(UnconfinedTestDispatcher(scheduler)) {
                            val effect = model.pendingScrollEffect.first { it?.generation == 41L }!!
                            // Capture at publication, not after drain: a second replacement must fail
                            // exact identity assertions even if it leaves an equivalent destination.
                            published.complete(effect to model.historyItems.value)
                        }
                        running.value = true
                        scheduler.runCurrent() // FIFO duration barrier is AFTER completion's sender.
                        release.complete(Unit)
                        settleUntil { model.activeTurnDuration.value != null }
                        assertTrue(published.isCompleted, "New navigation must publish before the FIFO barrier.")
                        val (effect, window) = published.await()
                        val child = assertIs<MessageHistoryItemViewModel>(window.peek(0))
                        assertSame(window, model.historyItems.value, "Completion must not replace the new window.")
                        assertSame(child, model.historyItems.value.peek(0))
                        assertSame(effect, model.pendingScrollEffect.value, "Completion must not withdraw the effect.")
                        assertEquals(41L, window.generation)
                        assertEquals(latest.value, child.index)
                        assertEquals(HistoryScrollTarget.Latest, effect.target)
                        assertEquals(41L, effect.generation)
                        assertFalse(window.hasNewer, "Captured end must not regress the actual latest cursor.")
                        assertTrue(model.followsLatest.value)
                        assertEquals(AgentHistoryLoadState.Ready, model.loadState.value)
                        assertTrue(oldLoading.isCancelled)
                        assertFalse(model.contains(old.generation, 20))
                        old[0]
                        assertSame(oldLoading, assertIs<MessageHistoryItemState.Loading>(oldChild.state.value).loadingJob)
                        assertFailsWith<IllegalStateException> { oldChild.readTimestamp() }
                        assertNotSame(oldChild, child)
                        window[0]
                        settleUntil { child.state.value is MessageHistoryItemState.Ready }
                        val expected = if (appendAfterCompletion) "appended" else "replacement"
                        assertEquals(navigationMessage(expected), assertIs<MessageHistoryItemState.Ready>(child.state.value).event)
                        child.readTimestamp()
                        running.value = false
                        settleUntil { model.activeTurnDuration.value == null }
                        // No renderer was mounted, and repeated/stale acknowledgments are harmless.
                        assertSame(window, model.historyItems.value)
                        model.acknowledgeScrollEffect(stale)
                        model.acknowledgeScrollEffect(stale)
                        assertSame(effect, model.pendingScrollEffect.value)
                        model.acknowledgeScrollEffect(effect)
                        assertNull(model.pendingScrollEffect.value)
                    } finally {
                        release.complete(Unit)
                        model.close()
                        owner.cancel()
                        settleUntil { owner.isCompleted }
                        owner.join()
                        repository.cancelAndJoin()
                    }
                }
            }
        }
    }

    test("no nonce same cursor destructive rewrite releases old child and withdraws its exact effect") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify {
                for (index in 1..8) it.index[index] = navigationMessage("$index")
                it.timestamp[1] = Clock.System.now()
            }
            val latest = MutableStateFlow(8)
            val state = MutableStateFlow<KodexAgentStateValue>(KodexAgentStateValue.UserMessage)
            val running = MutableStateFlow(false)
            val scheduler = TestCoroutineScheduler()
            val owner = Job(coroutineContext[Job])
            val model = createAgentHistoryViewModel(
                AgentHistorySource(runtime.storage, latest, state),
                CoroutineScope(coroutineContext + owner + StandardTestDispatcher(scheduler) + scheduler), running,
            )
            suspend fun settleUntil(condition: () -> Boolean) = withContext(Dispatchers.Default) {
                withTimeout(5.seconds) {
                    do { scheduler.runCurrent(); yield() } while (!condition())
                    scheduler.runCurrent()
                }
            }
            try {
                settleUntil { model.loadState.value == AgentHistoryLoadState.Ready }
                model.requestScrollToStorageIndex(8)
                settleUntil { model.pendingScrollEffect.value != null }
                val effect = model.pendingScrollEffect.value!!
                val old = model.historyItems.value
                val oldChild = assertIs<MessageHistoryItemViewModel>(assertIs<HistoryScrollTarget.Item>(effect.target).item)
                val oldLoading = assertIs<MessageHistoryItemState.Loading>(oldChild.state.value).loadingJob
                state.value = KodexAgentStateValue.ExternalWrite
                scheduler.runCurrent()
                runtime.modify {
                    it.revert(8)
                    it.index[8] = navigationMessage("rewritten without nonce")
                }
                assertEquals(8, runtime.latestIndex.value)
                // No cursor/nonce notification can perform this invalidation for the completion.
                state.value = KodexAgentStateValue.UserMessage
                scheduler.runCurrent()
                running.value = true
                scheduler.runCurrent()
                settleUntil { model.activeTurnDuration.value != null }
                val window = model.historyItems.value
                assertEquals(old.generation + 1, window.generation)
                assertNotSame(old, window)
                assertNull(model.pendingScrollEffect.value)
                model.acknowledgeScrollEffect(effect)
                assertNull(model.pendingScrollEffect.value)
                assertTrue(oldLoading.isCancelled)
                old[0]
                assertSame(oldLoading, assertIs<MessageHistoryItemState.Loading>(oldChild.state.value).loadingJob)
                assertFailsWith<IllegalStateException> { oldChild.readTimestamp() }
                assertFalse(model.contains(old.generation, 8))
                val child = assertIs<MessageHistoryItemViewModel>(window[0])
                assertNotSame(oldChild, child)
                settleUntil { child.state.value is MessageHistoryItemState.Ready }
                assertEquals(
                    navigationMessage("rewritten without nonce"),
                    assertIs<MessageHistoryItemState.Ready>(child.state.value).event,
                )
                model.requestScrollToLatest()
                settleUntil { model.pendingScrollEffect.value != null }
                val current = model.pendingScrollEffect.value!!
                assertEquals(window.generation, current.generation)
                model.acknowledgeScrollEffect(effect)
                assertSame(current, model.pendingScrollEffect.value)
                model.acknowledgeScrollEffect(current)
                assertNull(model.pendingScrollEffect.value)
            } finally {
                model.close()
                owner.cancel()
                settleUntil { owner.isCompleted }
                owner.join()
                repository.cancelAndJoin()
            }
        }
    }

    test("unmounted repeated exact targets persist and stale acknowledgments cannot consume them") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                for (index in listOf(3, 9, 20)) storage.index[index] = navigationMessage("$index")
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitNavigationReady()
                model.requestScrollToStorageIndex(3)
                val first = model.awaitEffect()
                val firstTarget = assertIs<HistoryScrollTarget.Item>(first.target).item
                assertEquals(3, assertIs<MessageHistoryItemViewModel>(firstTarget).index)
                assertSame(firstTarget, model.historyItems.value.peek(0))
                assertTrue(model.historyItems.value.hasNewer)
                assertFalse(model.followsLatest.value)

                // No renderer/mount or acknowledgment has occurred.
                model.awaitNavigationReady()
                assertSame(first, model.pendingScrollEffect.value)
                model.requestScrollToStorageIndex(3)
                val second = model.awaitEffect()
                assertNotSame(first, second, "StateFlow must not deduplicate repeated destinations.")
                assertSame(firstTarget, assertIs<HistoryScrollTarget.Item>(second.target).item)
                model.acknowledgeScrollEffect(first)
                assertSame(second, model.pendingScrollEffect.value)
                model.acknowledgeScrollEffect(second)
                assertNull(model.pendingScrollEffect.value)
                model.acknowledgeScrollEffect(first)
                assertNull(model.pendingScrollEffect.value)

                model.requestScrollToLatest()
                val latest = model.awaitEffect()
                assertEquals(HistoryScrollTarget.Latest, latest.target)
                assertEquals(20, assertIs<MessageHistoryItemViewModel>(model.historyItems.value.peek(0)).index)
                assertFalse(model.historyItems.value.hasNewer)
                assertTrue(model.followsLatest.value)
                model.requestScrollToLatest()
                val latestAgain = model.awaitEffect()
                assertNotSame(latest, latestAgain)
                model.acknowledgeScrollEffect(latest)
                assertSame(latestAgain, model.pendingScrollEffect.value)
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("nonce invalidation and close withdraw pending destinations and reject old windows") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                for (index in 1..8) storage.index[index] = navigationMessage("$index")
            }
            val nonce = MutableStateFlow(40L)
            val model = createAgentHistoryViewModel(
                AgentHistorySource(runtime.storage, runtime.latestIndex, runtime.state, nonce),
                supervisorChildScope(), MutableStateFlow(false),
            )
            try {
                model.awaitNavigationReady()
                model.requestScrollToStorageIndex(3)
                val effect = model.awaitEffect()
                val old = model.historyItems.value
                nonce.value = 41
                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        model.historyItems.first { it.generation == 41L && it.size > 0 }
                    }
                }
                model.awaitNavigationReady()
                assertNull(model.pendingScrollEffect.value)
                model.setFollowsLatest(model.historyItems.value, false)
                model.setFollowsLatest(old, true)
                assertFalse(model.followsLatest.value)
                model.reportViewport(old, listOf(old.peek(0)))
                model.acknowledgeScrollEffect(effect)
                assertNull(model.pendingScrollEffect.value)
                assertFalse(model.contains(old.generation, 3))

                model.requestScrollToStorageIndex(3)
                val beforeClose = model.awaitEffect()
                val window = model.historyItems.value
                model.close()
                assertNull(model.pendingScrollEffect.value)
                model.acknowledgeScrollEffect(beforeClose)
                model.requestScrollToStorageIndex(1)
                model.requestScrollToLatest()
                model.reportViewport(window, listOf(window.peek(0)))
                model.setFollowsLatest(window, true)
                window.requestOlder()
                window.requestNewer()
                assertNull(model.pendingScrollEffect.value)
                assertSame(window, model.historyItems.value)
                assertFalse(model.contains(window.generation, 3))
                assertFalse(model.followsLatest.value)
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("revert withdraws the exact pending target without retargeting its old child") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                for (index in 1..8) storage.index[index] = navigationMessage("$index")
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitNavigationReady()
                model.requestScrollToStorageIndex(7)
                val effect = model.awaitEffect()
                runtime.modify { it.revert(4) }
                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        model.historyItems.first { it.generation > effect.generation && it.size > 0 }
                    }
                }
                assertNull(model.pendingScrollEffect.value)
                model.acknowledgeScrollEffect(effect)
                assertNull(model.pendingScrollEffect.value)
                assertEquals(3, assertIs<MessageHistoryItemViewModel>(model.historyItems.value.peek(0)).index)
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("sparse paging protects the latest valid visible chunks and rejects stale or foreign reports") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                for (index in 1..40) storage.index[index * 3] = navigationMessage("$index")
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitNavigationReady()
                val stale = model.historyItems.value
                repeat(7) {
                    val window = model.historyItems.value
                    model.reportViewport(window, listOf(window.peek(0)))
                    model.page(window, newer = false)
                }
                val wide = model.historyItems.value
                assertEquals((120 downTo 99 step 3).toList(), wide.messageIndexes())
                val visible = wide.peek(6) // 102, not an ordinal storage cursor.
                val callerList = mutableListOf(visible)
                model.reportViewport(wide, callerList)
                callerList.clear() // Accepted reports must not borrow a mutable caller list.
                model.reportViewport(stale, listOf(stale.peek(0)))
                model.reportViewport(wide, listOf(
                    io.github.stream29.kodex.app.history.contract.item.ReasoningHistoryItemViewModel(
                        999, kotlin.time.Duration.ZERO,
                    ),
                ))
                model.setFollowsLatest(wide, false)
                model.page(wide, newer = false)
                val older = model.historyItems.value
                assertEquals(listOf(105, 102, 99, 96), older.messageIndexes())
                assertTrue(older.hasNewer)
                assertSame(visible, older.peek(1))
                model.setFollowsLatest(older, true)
                assertFalse(model.followsLatest.value, "Newest viewport is not newest storage.")

                model.reportViewport(older, listOf(visible))
                model.page(older, newer = true)
                val newer = model.historyItems.value
                assertEquals(listOf(108, 105, 102, 99), newer.messageIndexes())
                assertSame(visible, newer.peek(2))
                assertTrue(newer.hasOlder)
                assertTrue(newer.hasNewer)
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }

    test("visible Work Group identity protects its structural chunk rather than its child count") {
        coroutineScope {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val runtime = repository.open(repository.create()).runtime
            runtime.modify { storage ->
                storage.index[1] = navigationMessage("one")
                for (index in 2..20) storage.work[index] = StableTextToolEvent(
                    callId = "call-$index", name = "tool-$index",
                    arguments = JsonObject(emptyMap()), result = "done", success = true,
                )
                storage.index[21] = navigationMessage("seal")
                for (index in 22..30) storage.index[index] = navigationMessage("$index")
            }
            val model = createAgentHistoryViewModel(runtime, supervisorChildScope())
            try {
                model.awaitNavigationReady()
                repeat(9) {
                    val window = model.historyItems.value
                    model.reportViewport(window, listOf(window.peek(0)))
                    model.page(window, newer = false)
                }
                val wide = model.historyItems.value
                val group = assertIs<WorkGroupHistoryItemViewModel>(wide.peek(wide.size - 1))
                assertEquals(19, group.itemCount)
                assertEquals(2..20, group.indexRange)
                model.reportViewport(wide, listOf(group))
                model.page(wide, newer = false)
                val bounded = model.historyItems.value
                assertEquals(4, bounded.size, "One neighboring chunk, group chunk and older chunk.")
                assertSame(group, bounded.peek(2))
                assertEquals(21, assertIs<MessageHistoryItemViewModel>(bounded.peek(1)).index)
                assertEquals(1, assertIs<MessageHistoryItemViewModel>(bounded.peek(3)).index)
            } finally {
                model.close()
                repository.cancelAndJoin()
            }
        }
    }
}

private fun navigationMessage(text: String): StableUserMessage =
    StableUserMessage(listOf(ContentItem.InputText(text)))

private fun HistoryItemWindow.messageIndexes(): List<Int> =
    List(size) { assertIs<MessageHistoryItemViewModel>(peek(it)).index }

private suspend fun AgentHistoryViewModel.awaitNavigationReady() = withContext(Dispatchers.Default) {
    withTimeout(5.seconds) {
        val state = loadState.first { it == AgentHistoryLoadState.Ready || it is AgentHistoryLoadState.Failed }
        check(state == AgentHistoryLoadState.Ready) { "$state" }
    }
}

private suspend fun AgentHistoryViewModel.awaitEffect(): HistoryScrollEffect =
    withContext(Dispatchers.Default) {
        withTimeout(5.seconds) { pendingScrollEffect.first { it != null }!! }
    }

private suspend fun AgentHistoryViewModel.page(window: HistoryItemWindow, newer: Boolean) {
    awaitNavigationReady()
    if (newer) window.requestNewer() else window.requestOlder()
    withContext(Dispatchers.Default) {
        withTimeout(5.seconds) { historyItems.first { it !== window } }
    }
    awaitNavigationReady()
}
