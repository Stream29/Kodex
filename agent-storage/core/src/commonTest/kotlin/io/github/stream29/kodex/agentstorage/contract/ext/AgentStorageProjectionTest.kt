package io.github.stream29.kodex.agentstorage.contract.ext

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.LlmRequestMeta
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.SteerSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.UnstableSnapshot
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.OpenAiModelId
import kotlin.test.assertEquals
import kotlin.time.Instant

val agentStorageProjectionTest by testSuite {
    test("the active window excludes non-conversation commits sharing dialogue indexes") {
        val storage = InMemoryKodexAgentStorage.empty()
        val stamp = Instant.parse("2026-11-18T00:00:00Z")
        val prior = StableUserMessage(listOf(ContentItem.InputText("before checkpoint")))
        val after = StableUserMessage(listOf(ContentItem.InputText("after checkpoint")))
        val point = StableContextCompaction(encryptedContent = "fixture", retainedItems = listOf(prior))
        val settings = KodexAgentSettings(OpenAiModelId("fixture"))
        storage.commit(listOf(prior, settings), stamp)
        storage.commit(listOf(point, settings, LlmRequestMeta.Response(totalTokens = 5L)), stamp)
        val end = storage.commit(
            listOf(after, UnstableSnapshot(emptyList()), SteerSnapshot(listOf(after)), AskUserSnapshot(emptyList())),
            stamp,
        )
        assertEquals(listOf<AgentCommit>(point, after), storage.activeMessageWindowAt(end))
    }
}
