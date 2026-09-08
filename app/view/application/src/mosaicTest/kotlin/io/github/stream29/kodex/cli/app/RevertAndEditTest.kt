package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableDeveloperMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.app.agent.contract.AgentHistoryActionState
import io.github.stream29.kodex.app.agent.contract.AgentViewModel
import io.github.stream29.kodex.app.history.contract.item.MessageHistoryItemState
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

val revertAndEditTest by testSuite {
    test("only ready entirely textual user messages round trip without trimming") {
        val text = "  第一行\n\n第二行  "
        fun ready(content: List<ContentItem>) =
            MessageHistoryItemState.Ready(StableUserMessage(content), Duration.ZERO)
        assertEquals(text, ready(listOf(ContentItem.InputText(text))).revertAndEditText())
        assertEquals(
            "one\n two ",
            ready(listOf(ContentItem.InputText("one\n"), ContentItem.OutputText(" two "))).revertAndEditText(),
        )
        assertEquals("", ready(emptyList()).revertAndEditText())
        assertNull(ready(listOf(ContentItem.InputImage("https://example.invalid/image"))).revertAndEditText())
        assertNull(
            ready(listOf(ContentItem.InputText(text), ContentItem.InputImage("data:image/png;base64,AA==")))
                .revertAndEditText(),
        )
        assertNull(
            MessageHistoryItemState.Ready(
                StableAssistantMessage(listOf(ContentItem.OutputText(text))), Duration.ZERO,
            ).revertAndEditText(),
        )
        assertNull(
            MessageHistoryItemState.Ready(
                StableDeveloperMessage(listOf(ContentItem.InputText(text))), Duration.ZERO,
            ).revertAndEditText(),
        )
        assertNull(MessageHistoryItemState.Failed.revertAndEditText())
        assertNull(null.revertAndEditText())
        val loading = Job()
        try {
            assertNull(MessageHistoryItemState.Loading(loading).revertAndEditText())
        } finally {
            loading.cancel()
        }
    }

    test("first user message reverts to empty history and replaces only its owner's draft") {
        val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
        val store = testSessionViewModelRegistry(repository, this)
        val text = "  修改这条消息\n保留换行  "
        try {
            val session = store.create { KodexAgentSettings(model = OpenAiModelId("test-model")) }
            val other = store.create { KodexAgentSettings(model = OpenAiModelId("test-model")) }
            val root = repository.open(session.sessionIndex)
            root.runtime.modify { storage ->
                storage.index[2] = StableUserMessage(listOf(ContentItem.InputText(text)))
                storage.index[8] = StableAssistantMessage(listOf(ContentItem.OutputText("remove")))
            }
            val agent = session.rootAgent
            agent.composer.update("replace this draft", 0)
            other.rootAgent.composer.update("other draft", 3)
            val generation = withContext(Dispatchers.Default) {
                agent.history.requestScrollToStorageIndex(2)
                withTimeout(5.seconds) {
                    agent.history.historyItems.first { agent.history.contains(it.generation, 2) }.generation
                }
            }
            revertAndEdit(agent, 2, generation, text)
            assertTrue(root.storage.index.indexesIn(0..10).isEmpty())
            assertEquals(text, agent.composer.state.value.text)
            assertEquals(text.length, agent.composer.state.value.cursorOffset)
            assertEquals("other draft", other.rootAgent.composer.state.value.text)
            assertIs<AgentHistoryActionState.None>(agent.historyAction.value)
            assertFalse(agent.execution.value.running)
            assertNull(root.runtime.runningTurn.value)
        } finally {
            store.shutdown()
            repository.cancelAndJoin()
        }
    }

    test("frontend waits for success and leaves draft unchanged on failure") {
        val fixture = SessionViewModelTestFixture.create(this)
        try {
            val real = fixture.persistedSession("Draft owner").rootAgent
            real.composer.update("existing draft", 4)
            val original = real.composer.state.value
            val started = CompletableDeferred<Unit>()
            val result = CompletableDeferred<Unit>()
            val agent = object : AgentViewModel by real {
                override suspend fun revertHistory(untilExclusive: Int, expectedGeneration: Long) {
                    assertEquals(8, untilExclusive)
                    assertEquals(3L, expectedGeneration)
                    started.complete(Unit)
                    result.await()
                }
            }
            // Catch inside the child so the deliberate failure does not cancel the test scope.
            val waiting = async(start = CoroutineStart.UNDISPATCHED) {
                assertFailsWith<IllegalStateException> { revertAndEdit(agent, 8, 3, "replacement") }
            }
            started.await()
            assertEquals(original, real.composer.state.value)
            result.completeExceptionally(IllegalStateException("storage failure"))
            waiting.await()
            assertEquals(original, real.composer.state.value)
        } finally {
            fixture.close()
        }
    }
}
