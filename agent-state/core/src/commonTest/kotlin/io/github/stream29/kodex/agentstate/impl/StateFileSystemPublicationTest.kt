package io.github.stream29.kodex.agentstate.impl

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.contract.forcedCompact
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.agentstorage.contract.getSettingsAt
import io.github.stream29.kodex.agentstorage.contract.getSettingsExact
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.RemoteCompactionV2Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

val stateFileSystemPublicationTest by testSuite {
    testFixture {
        testSuiteCoroutineScope.supervisorChildScope()
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
        for (cas in listOf(false, true)) {
            for (cancelled in listOf(false, true)) {
                test("B2 post-commit ${if (cancelled) "cancellation" else "failure"} resyncs durable settings with cas=$cas") {
                    val primary = if (cancelled) CancellationException("post-commit cancelled")
                    else IOException("post-commit write failed")
                    val cleanup = IOException("post-commit cleanup failed")
                    val backing = InMemoryKodexAgentStorage.empty()
                    val storage = StatePublicationStorage(backing)
                    storage.initialize(KodexAgentSettings(OpenAiModelId("initial")))
                    val agent = KodexAgentState(mockOpenAiClient(), storage)
                    try {
                        agent.appendUserMessage(listOf(ContentItem.InputText("legal history")))
                        val before = storage.getSettingsAt(agent.latestIndex.value)
                        val history = storage.getCommitsIn(0..Int.MAX_VALUE, AgentCommitType.IndexTypes)
                        val updated = before.copy(threadName = "durably written")
                        storage.postCommitFailure = primary
                        storage.postCommitCleanupFailure = cleanup

                        assertSame(primary, observeStateWriteFailure {
                            if (cas) agent.compareAndSetSettings(before, updated)
                            else agent.updateSettings(updated)
                        })

                        assertEquals(listOf(cleanup), primary.suppressedExceptions)
                        assertTrue(storage.cleanupAttempted)
                        assertEquals(updated, backing.getSettingsExact(2))
                        assertEquals(2, backing.latestIndex())
                        assertEquals(2, agent.latestIndex.value)
                        assertEquals(updated, agent.storage.getSettingsAt(agent.latestIndex.value))
                        assertEquals(KodexAgentStateValue.UserMessage, agent.state.value)
                        assertEquals(history, backing.getCommitsIn(0..Int.MAX_VALUE, AgentCommitType.IndexTypes))
                        assertFalse(agent.compareAndSetSettings(before, before.copy(threadName = "stale")))
                        assertTrue(agent.compareAndSetSettings(updated, updated.copy(threadName = "next")))
                        assertEquals(3, agent.latestIndex.value)
                        assertEquals(updated, backing.getSettingsExact(2))
                        assertEquals(before, backing.getSettingsExact(0))
                        assertEquals(listOf(0, 2, 3), backing.indexesIn(0..3, AgentCommitType.SettingsTypes))
                        assertEquals(listOf(0, 1, 2, 3), backing.indexesIn(0..3, AgentCommitType.TimestampTypes))
                    } finally {
                        agent.cancelAndJoin()
                    }
                }
            }
        }

        test("B2 record publication then cleanup failure resyncs State and never replaces that record") {
            val backing = InMemoryKodexAgentStorage.empty()
            val storage = StatePublicationStorage(backing)
            storage.initialize(KodexAgentSettings(OpenAiModelId("initial")))
            val agent = KodexAgentState(mockOpenAiClient(), storage)
            try {
                val before = storage.getSettingsAt(0)
                val published = before.copy(threadName = "published despite failure")
                val failure = IOException("settings temporary cleanup failed after entry move")
                storage.settingsCleanupFailure = failure
                assertSame(failure, observeStateWriteFailure {
                    agent.compareAndSetSettings(before, published)
                })
                assertEquals(published, backing.getSettingsExact(1))
                assertEquals(1, storage.latestIndex())
                assertEquals(1, agent.latestIndex.value)
                assertEquals(KodexAgentStateValue.Empty, agent.state.value)
                assertEquals(emptyList(), failure.suppressedExceptions)
                assertTrue(agent.compareAndSetSettings(published, published.copy(threadName = "next")))
                assertEquals(2, agent.latestIndex.value)
                assertEquals(published, backing.getSettingsExact(1))
                assertEquals(before, backing.getSettingsExact(0))
            } finally {
                agent.cancelAndJoin()
            }
        }

        for (cancelled in listOf(false, true)) {
            test("checkpoint waits for admitted filesystem CAS with cancelled=$cancelled") {
                val backing = InMemoryKodexAgentStorage.empty()
                val storage = StatePublicationStorage(backing)
                storage.initialize(KodexAgentSettings(OpenAiModelId("initial")))
                val networkEntered = CompletableDeferred<Unit>()
                val networkRelease = CompletableDeferred<Unit>()
                val commitEntered = CompletableDeferred<Unit>()
                val commitRelease = CompletableDeferred<Unit>()
                val networkReturned = CompletableDeferred<Unit>()
                val agent = KodexAgentState(mockOpenAiClient {
                    createRemoteCompactionV2Response {
                        networkEntered.complete(Unit)
                        networkRelease.await()
                        networkReturned.complete(Unit)
                        RemoteCompactionV2Response(ResponseItem.Compaction(encryptedContent = "result"), null)
                    }
                }, storage)
                agent.appendUserMessage(listOf(ContentItem.InputText("compact")))
                val before = storage.getSettingsAt(agent.latestIndex.value)
                val compacting = async(start = CoroutineStart.UNDISPATCHED) { agent.forcedCompact() }
                var writer: kotlinx.coroutines.Deferred<Boolean>? = null
                try {
                    networkEntered.await()
                    storage.postCommitGate = commitEntered to commitRelease
                    val latest = before.copy(threadName = "title", model = OpenAiModelId("selected"))
                    val cas = async(start = CoroutineStart.UNDISPATCHED) {
                        agent.compareAndSetSettings(before, latest)
                    }
                    writer = cas
                    commitEntered.await()
                    assertEquals(latest, backing.getSettingsExact(2))
                    assertEquals(1, agent.latestIndex.value)
                    networkRelease.complete(Unit)
                    networkReturned.await()
                    assertFalse(cas.isCompleted)
                    assertFalse(compacting.isCompleted)
                    assertEquals(KodexAgentStateValue.Compacting, agent.state.value)
                    if (cancelled) compacting.cancel()
                    commitRelease.complete(Unit)
                    assertTrue(cas.await())
                    if (cancelled) {
                        assertFailsWith<CancellationException> { compacting.await() }
                        compacting.join()
                        assertEquals(latest, storage.getSettingsAt(2))
                        assertEquals(2, agent.latestIndex.value)
                        assertEquals(listOf(0, 2), storage.indexesIn(0..4, AgentCommitType.SettingsTypes))
                        assertEquals(listOf(0, 1, 2), storage.indexesIn(0..4, AgentCommitType.TimestampTypes))
                        assertEquals(emptyList(), storage.indexesIn(0..4, AgentCommitType.WorkTypes))
                    } else {
                        assertEquals(4, compacting.await())
                        val committed = storage.getSettingsAt(4)
                        assertEquals(latest.copy(
                            windowNumber = latest.windowNumber + 1,
                            previousWindowId = latest.windowId,
                            windowId = committed.windowId,
                        ), committed)
                        assertEquals(listOf(0, 2, 3), storage.indexesIn(0..4, AgentCommitType.SettingsTypes))
                        assertEquals(listOf(0, 1, 2, 4), storage.indexesIn(0..4, AgentCommitType.TimestampTypes))
                        assertEquals(4, agent.latestIndex.value)
                    }
                    assertEquals(KodexAgentStateValue.UserMessage, agent.state.value)
                } finally {
                    commitRelease.complete(Unit)
                    networkRelease.complete(Unit)
                    writer?.cancelAndJoin()
                    compacting.cancelAndJoin()
                    agent.cancelAndJoin()
                }
            }
        }
    }
}

private class StatePublicationStorage(
    private val delegate: MutableKodexAgentStorage,
) : MutableKodexAgentStorage by delegate {
    var postCommitFailure: Throwable? = null
    var postCommitCleanupFailure: Throwable? = null
    var settingsCleanupFailure: Throwable? = null
    var postCommitGate: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>? = null
    var cleanupAttempted = false
        private set

    override suspend fun commit(commits: List<AgentCommit>): Int {
        val index = delegate.commit(commits)
        if (commits.any { it is KodexAgentSettings }) {
            postCommitGate?.also {
                postCommitGate = null
                it.first.complete(Unit)
                it.second.await()
            }
            postCommitFailure?.also { failure ->
                postCommitFailure = null
                cleanupAttempted = true
                postCommitCleanupFailure?.also { cleanup ->
                    postCommitCleanupFailure = null
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
                if (failure is CancellationException) currentCoroutineContext().cancel(failure)
                throw failure
            }
            settingsCleanupFailure?.also { failure ->
                settingsCleanupFailure = null
                currentCoroutineContext().ensureActive()
                cleanupAttempted = true
                throw failure
            }
        }
        return index
    }
}

private suspend fun observeStateWriteFailure(operation: suspend () -> Unit): Throwable = coroutineScope {
    val observed = CompletableDeferred<Throwable>()
    launch {
        observed.complete(runCatching { operation() }.exceptionOrNull() ?: error("Expected failure"))
    }.join()
    observed.await()
}
