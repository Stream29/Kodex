package io.github.stream29.kodex.agentstorage.contract.ext

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.CleanCompactionPoint
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableWebSearchCall
import io.github.stream29.kodex.agentstorage.contract.IndexVersioned
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.indexes
import io.github.stream29.kodex.agentstorage.contract.indexesDescending
import io.github.stream29.kodex.agentstorage.contract.values
import io.github.stream29.kodex.agentstorage.contract.valuesDescending
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ResponseItem
import kotlinx.coroutines.flow.toList
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

private fun storage(): InMemoryKodexAgentStorage =
    InMemoryKodexAgentStorage(
        io.github.stream29.kodex.openai.KodexAgentSettings(
            model = OpenAiModelId("test-model"),
        ),
    )

private fun user(text: String): StableUserMessage =
    StableUserMessage(listOf(ContentItem.InputText(text)))

private fun assistant(text: String): StableAssistantMessage =
    StableAssistantMessage(listOf(ContentItem.OutputText(text)))

private fun work(status: String): StableWebSearchCall =
    StableWebSearchCall(ResponseItem.WebSearchCall(status = status))

private class CountingTimeline<T>(
    private val delegate: IndexVersioned<T>,
) : IndexVersioned<T> {
    var exactReads: Int = 0
    val ranges: MutableList<IntRange> = mutableListOf()
    val valueRanges: MutableList<IntRange> = mutableListOf()

    override suspend fun latestIndex(): Int = delegate.latestIndex()

    override suspend fun get(index: Int): T = delegate[index]

    override suspend fun getExact(index: Int): T? {
        exactReads += 1
        return delegate.getExact(index)
    }

    override suspend fun floorToIndex(index: Int): Int? = delegate.floorToIndex(index)

    override suspend fun ceilToIndex(index: Int): Int? = delegate.ceilToIndex(index)

    override suspend fun indexesIn(range: IntRange): List<Int> = delegate.indexesIn(range)
        .also { ranges += range }

    override suspend fun valuesIn(range: IntRange): List<Pair<Int, T>> = delegate.valuesIn(range)
        .also { valueRanges += range }
}

private class CountingStorage(
    private val delegate: KodexAgentStorage,
) : KodexAgentStorage {
    val countedIndex = CountingTimeline(delegate.index)
    val countedWork = CountingTimeline(delegate.work)

    override val uri: String = delegate.uri
    override val index: IndexVersioned<io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.CleanIndexEntry>
        get() = countedIndex
    override val work: IndexVersioned<io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableWorkEvent>
        get() = countedWork
    override val settings: IndexVersioned<io.github.stream29.kodex.openai.KodexAgentSettings>
        get() = delegate.settings
    override val timestamp: IndexVersioned<Instant>
        get() = delegate.timestamp
    override val tokenCount: IndexVersioned<Long>
        get() = delegate.tokenCount
    override val unstable: IndexVersioned<List<io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent>>
        get() = delegate.unstable
}

val agentStorageContractExtTest by testSuite {
    test("index flows enumerate sparse indexes in both directions") {
        val mutable = storage()
        mutable.index[1] = user("one")
        mutable.index[3] = assistant("three")
        val counted = CountingTimeline(mutable.index)

        assertEquals(listOf(1, 3), counted.indexes().toList())
        assertEquals(listOf(3, 1), counted.indexesDescending(3).toList())
        assertEquals(
            listOf(1, 3),
            counted.values().toList().map { (index, _) -> index },
        )
        assertEquals(
            listOf(3, 1),
            counted.valuesDescending(3).toList().map { (index, _) -> index },
        )
        assertEquals(
            listOf(1, 3),
            counted.valuesIn(0..3).map { (index, _) -> index },
        )
        assertTrue(counted.ranges.any { it == 1..2 })
        assertTrue(counted.valueRanges.contains(0..3))
    }

    test("compaction prefix stops fetching after its retained token budget") {
        val mutable = storage()
        repeat(500) { offset ->
            mutable.index[offset + 1] = user("x".repeat(1_000))
        }
        val counted = CountingStorage(mutable)

        val prefix = counted.buildCompactionPrefix(beforeIndex = 501)

        assertTrue(prefix.isNotEmpty())
        assertTrue(prefix.size < 500)
        assertTrue(counted.countedIndex.exactReads < 500)
    }

    test("active message window retains prefix and preserves post-point events") {
        val mutable = storage()
        mutable.index[1] = user("retained")
        mutable.index[2] = CleanCompactionPoint
        mutable.work[3] = StableContextCompaction(encryptedContent = "encrypted")
        mutable.index[4] = user("current")
        mutable.work[5] = work("current-work")

        val window = mutable.activeMessageWindowAt(5)

        assertEquals(
            listOf(
                StableUserMessage::class,
                StableContextCompaction::class,
                StableUserMessage::class,
                StableWebSearchCall::class,
            ),
            window.map { it::class },
        )
    }

    test("active message window keeps the whole history when no point exists") {
        val mutable = InMemoryKodexAgentStorage.empty()
        mutable.index[0] = user("first")
        mutable.work[1] = work("second")

        val window = mutable.activeMessageWindowAt(1)

        assertEquals(
            listOf(StableUserMessage::class, StableWebSearchCall::class),
            window.map { it::class },
        )
    }

}
