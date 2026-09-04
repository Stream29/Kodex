package io.github.stream29.kodex.cli.history

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.CleanIndexEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.CleanCompactionPoint
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableTextToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableWorkEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.UnstableCleanEvent
import io.github.stream29.kodex.agentstorage.contract.IndexVersioned
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

val indexHistoryReadTest by testSuite {
    test("a singleton sealed work interval is still one work group") {
        val mutable = historyStorage()
        mutable.index[1] = historyUser("prompt")
        mutable.work[2] = historyWork(2)
        mutable.index[3] = historyAssistant("done")

        val page = mutable.readHistoryChunk(fromInclusive = 3)

        assertEquals(2, page.items.size)
        val group = assertIs<HistoryProjectionItem.WorkGroup>(page.items[1])
        assertEquals(2..2, group.indexRange)
        assertEquals(1, group.itemCount)
        assertEquals(1, page.nextOlderIndex)
    }

    test("an anchor and its sealed work group are not split across pages") {
        val mutable = historyStorage()
        mutable.index[1] = historyUser("prompt")
        mutable.work[2] = historyWork(2)
        mutable.index[3] = historyAssistant("done")

        val page = mutable.readHistoryChunk(fromInclusive = 3)

        assertEquals(2, page.items.size)
        assertIs<HistoryProjectionItem.Stable>(page.items[0])
        assertIs<HistoryProjectionItem.WorkGroup>(page.items[1])
        assertEquals(1, page.nextOlderIndex)
    }

    test("compaction point and output form one item outside the work group") {
        val mutable = historyStorage()
        mutable.index[1] = historyUser("prompt")
        mutable.index[2] = CleanCompactionPoint
        mutable.work[3] = StableContextCompaction(encryptedContent = "encrypted")
        mutable.work[4] = historyWork(4)
        mutable.index[5] = historyAssistant("done")
        val counted = CountingHistoryStorage(mutable)

        val page = counted.readAllHistory(fromInclusive = 5)

        assertEquals(4, page.items.size)
        assertEquals(
            listOf(5, 4, 3, 1),
            page.items.map { item ->
                when (item) {
                    is HistoryProjectionItem.Stable -> item.descriptor.index
                    is HistoryProjectionItem.WorkGroup -> item.indexRange.last
                }
            },
        )
        assertEquals(4..4, assertIs<HistoryProjectionItem.WorkGroup>(page.items[1]).indexRange)
        assertEquals(
            HistoryItemKind.ContextCompaction,
            assertIs<HistoryProjectionItem.Stable>(page.items[2]).descriptor.kind,
        )
        assertEquals(0, counted.workTimeline.valuesReturned)
    }

    test("one sealed work interval is one top-level item without payload reads") {
        val workCount = 50_000
        val mutable = historyStorage()
        mutable.index[1] = historyUser("prompt")
        repeat(workCount) { offset ->
            mutable.work[offset + 2] = historyWork(offset)
        }
        val finalIndex = workCount + 2
        mutable.index[finalIndex] = historyAssistant("done")

        val counted = CountingHistoryStorage(mutable)
        val started = TimeSource.Monotonic.markNow()
        val page = counted.readAllHistory(fromInclusive = finalIndex)
        val elapsed = started.elapsedNow()

        assertEquals(3, page.items.size)
        assertEquals(null, page.nextOlderIndex)
        assertIs<HistoryProjectionItem.Stable>(page.items[0])
        val group = assertIs<HistoryProjectionItem.WorkGroup>(page.items[1])
        assertEquals(2..(workCount + 1), group.indexRange)
        assertEquals(workCount, group.itemCount)
        assertIs<HistoryProjectionItem.Stable>(page.items[2])
        assertEquals(0, counted.workTimeline.exactReads)
        assertEquals(0, counted.workTimeline.valuesReturned)
        assertTrue(
            elapsed < 5.seconds,
            "Index-only projection of $workCount work items took $elapsed.",
        )
        println("index History chunk: $workCount sealed work indexes in $elapsed")
    }

    test("open work suffix reads one payload per LazyColumn demand") {
        val workCount = 20_000
        val mutable = historyStorage()
        mutable.index[1] = historyUser("prompt")
        repeat(workCount) { offset ->
            mutable.work[offset + 2] = historyWork(offset)
        }
        val counted = CountingHistoryStorage(mutable)

        val page = counted.readHistoryChunk(fromInclusive = workCount + 1)

        assertEquals(1, page.items.size)
        assertNotNull(page.nextOlderIndex)
        assertTrue(page.items.all { item -> item is HistoryProjectionItem.Stable })
        assertEquals(0, counted.workTimeline.exactReads)
        assertEquals(1, counted.workTimeline.valuesReturned)
    }

    test("random index seek reads one structural chunk") {
        val mutable = historyStorage()
        repeat(20_000) { offset ->
            val index = offset + 1
            mutable.index[index] = historyUser("$index")
        }
        val counted = CountingHistoryStorage(mutable)

        val page = counted.readHistoryChunk(fromInclusive = 10_000)

        assertEquals(1, page.items.size)
        assertEquals(
            10_000,
            assertIs<HistoryProjectionItem.Stable>(page.items.first()).descriptor.index,
        )
        assertEquals(9_999, page.nextOlderIndex)
        assertTrue(counted.indexTimeline.exactReads <= 2)
        assertEquals(0, counted.workTimeline.valuesReturned)
    }

    test("newer seek crosses one large sealed interval without payload reads") {
        val workCount = 50_000
        val mutable = historyStorage()
        mutable.index[1] = historyUser("prompt")
        repeat(workCount) { offset ->
            mutable.work[offset + 2] = historyWork(offset)
        }
        val finalIndex = workCount + 2
        mutable.index[finalIndex] = historyAssistant("done")
        val counted = CountingHistoryStorage(mutable)

        val started = TimeSource.Monotonic.markNow()
        val chunk = assertNotNull(
            counted.readNewerHistoryChunk(
                afterExclusive = 1,
                snapshotIndex = finalIndex,
            ),
        )
        val elapsed = started.elapsedNow()

        assertEquals(2, chunk.items.size)
        assertEquals(
            finalIndex,
            assertIs<HistoryProjectionItem.Stable>(chunk.items[0]).descriptor.index,
        )
        assertEquals(
            2..(workCount + 1),
            assertIs<HistoryProjectionItem.WorkGroup>(chunk.items[1]).indexRange,
        )
        assertEquals(0, counted.workTimeline.exactReads)
        assertEquals(0, counted.workTimeline.valuesReturned)
        assertTrue(
            elapsed < 5.seconds,
            "Forward index projection of $workCount sealed work items took $elapsed.",
        )
        println("newer index History chunk: $workCount sealed work indexes in $elapsed")
    }
}

private suspend fun KodexAgentStorage.readAllHistory(
    fromInclusive: Int,
): LoadedHistoryChunk {
    val items = mutableListOf<HistoryProjectionItem>()
    var nextIndex: Int? = fromInclusive
    while (nextIndex != null) {
        val chunk = readHistoryChunk(nextIndex)
        items += chunk.items
        nextIndex = chunk.nextOlderIndex
    }
    return LoadedHistoryChunk(items, null)
}

private class CountingHistoryTimeline<T>(
    private val delegate: IndexVersioned<T>,
) : IndexVersioned<T> {
    var exactReads: Int = 0
    var valuesReturned: Int = 0

    override suspend fun latestIndex(): Int = delegate.latestIndex()

    override suspend fun get(index: Int): T = delegate[index]

    override suspend fun getExact(index: Int): T? {
        exactReads += 1
        return delegate.getExact(index)
    }

    override suspend fun floorToIndex(index: Int): Int? =
        delegate.floorToIndex(index)

    override suspend fun ceilToIndex(index: Int): Int? =
        delegate.ceilToIndex(index)

    override suspend fun indexesIn(range: IntRange): List<Int> =
        delegate.indexesIn(range)

    override suspend fun valuesIn(range: IntRange): List<Pair<Int, T>> =
        delegate.valuesIn(range).also { values -> valuesReturned += values.size }
}

private class CountingHistoryStorage(
    delegate: KodexAgentStorage,
) : KodexAgentStorage {
    val indexTimeline = CountingHistoryTimeline(delegate.index)
    val workTimeline = CountingHistoryTimeline(delegate.work)

    override val uri: String = delegate.uri
    override val index: IndexVersioned<CleanIndexEntry> = indexTimeline
    override val work: IndexVersioned<StableWorkEvent> = workTimeline
    override val settings: IndexVersioned<KodexAgentSettings> = delegate.settings
    override val timestamp: IndexVersioned<Instant> = delegate.timestamp
    override val tokenCount: IndexVersioned<Long> = delegate.tokenCount
    override val unstable: IndexVersioned<List<UnstableCleanEvent>> = delegate.unstable
}

private fun historyStorage(): InMemoryKodexAgentStorage =
    InMemoryKodexAgentStorage(
        KodexAgentSettings(model = OpenAiModelId("test-model")),
    )

private fun historyUser(text: String): StableUserMessage =
    StableUserMessage(listOf(ContentItem.InputText(text)))

private fun historyAssistant(text: String): StableAssistantMessage =
    StableAssistantMessage(listOf(ContentItem.OutputText(text)))

private fun historyWork(index: Int): StableTextToolEvent =
    StableTextToolEvent(
        callId = "call-$index",
        name = "tool-$index",
        arguments = JsonObject(emptyMap()),
        result = "done",
        success = true,
    )
