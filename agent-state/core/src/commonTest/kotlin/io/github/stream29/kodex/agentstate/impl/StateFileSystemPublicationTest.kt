package io.github.stream29.kodex.agentstate.impl

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.contract.forcedCompact
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.contract.MutableKodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.agentstorage.contract.getCommitsIn
import io.github.stream29.kodex.agentstorage.contract.getSettingsAt
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
import kotlin.time.Clock
import kotlin.time.Instant
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
                        assertEquals(listOf(0, 2), storage.getCommitsIn(0..4, AgentCommitType.SettingsTypes).map { it.index })
                        assertEquals(0, storage.countCommitsIn(0..4, AgentCommitType.WorkTypes))
                    } else {
                        assertEquals(3, compacting.await())
                        val committed = storage.getSettingsAt(3)
                        assertEquals(latest.copy(
                            windowNumber = latest.windowNumber + 1,
                            previousWindowId = latest.windowId,
                            windowId = committed.windowId,
                        ), committed)
                        assertEquals(listOf(0, 2, 3), storage.getCommitsIn(0..4, AgentCommitType.SettingsTypes).map { it.index })
                        assertEquals(3, agent.latestIndex.value)
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
    var postCommitGate: Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>>? = null

    override suspend fun commit(commits: List<AgentCommit>, timestamp: Instant): Int {
        val index = delegate.commit(commits, timestamp)
        if (commits.any { it is KodexAgentSettings }) {
            postCommitGate?.also {
                postCommitGate = null
                it.first.complete(Unit)
                it.second.await()
            }
        }
        return index
    }

    override suspend fun commit(commits: List<AgentCommit>): Int =
        commit(commits, Clock.System.now())
}
