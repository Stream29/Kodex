@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.utils.stream

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

val inMemoryStreamServiceTest by testSuite(
    testConfig = TestConfig.testScope(isEnabled = false),
) {
    test("append creates a stream and completed history replays with defensive payload copies") {
        runTest {
            val service = InMemoryStreamService()
            val payload = byteArrayOf(1, 2)
            assertFalse(service.exists("history"))
            assertNull(service.latest("history"))
            val first = service.append("history", payload)
            payload[0] = 99
            val second = service.append("history", byteArrayOf(3))
            service.complete("history")
            service.complete("history")
            service.complete("absent")

            assertTrue(service.exists("history"))
            assertFalse(service.exists("absent"))
            val expected = listOf(StreamEntry(first, byteArrayOf(1, 2)), StreamEntry(second, byteArrayOf(3)))
            val latest = assertNotNull(service.latest("history"))
            assertEquals(expected.last(), latest)
            assertEquals(expected.last().hashCode(), latest.hashCode())
            latest.payload[0] = 99
            val replay = service.read("history").toList()
            assertEquals(expected, replay)
            replay.first().payload[0] = 99
            assertEquals(expected, service.read("history").toList())
            assertFailsWith<IllegalStateException> { service.append("history", byteArrayOf(4)) }
        }
    }

    test("append MAXLEN keeps only the intended entries") {
        runTest {
            val service = InMemoryStreamService()
            assertFailsWith<IllegalArgumentException> { service.append("snapshot", byteArrayOf(), maxLen = 0) }
            assertFailsWith<IllegalArgumentException> { service.append("snapshot", byteArrayOf(), maxLen = -1) }
            assertFalse(service.exists("snapshot"))
            service.create("snapshot")
            assertTrue(service.exists("snapshot"))
            assertNull(service.latest("snapshot"))
            val trimmed = service.append("snapshot", byteArrayOf(1), maxLen = 1)
            service.append("snapshot", byteArrayOf(2), maxLen = 2)
            val retained = service.append("snapshot", byteArrayOf(3), maxLen = 1)
            service.complete("snapshot")
            assertEquals(listOf(StreamEntry(retained, byteArrayOf(3))), service.read("snapshot", trimmed).toList())

            service.append("implicit", byteArrayOf(1), maxLen = 1)
            val latest = service.append("implicit", byteArrayOf(2), maxLen = 1)
            assertEquals(StreamEntry(latest, byteArrayOf(2)), service.latest("implicit"))
            service.complete("implicit")
            assertEquals(listOf(StreamEntry(latest, byteArrayOf(2))), service.read("implicit").toList())
        }
    }

    test("afterId and tail cursors start at collection and use numerical order") {
        runTest {
            val service = InMemoryStreamService()
            val tail = service.read("events", "$")
            val ids = (1..9).map { service.append("events", byteArrayOf(it.toByte())) }
            val tailReader = async(start = CoroutineStart.UNDISPATCHED) { tail.toList() }
            val id10 = service.append("events", byteArrayOf(10))
            val id11 = service.append("events", byteArrayOf(11))
            service.complete("events")
            val expected = listOf(StreamEntry(id10, byteArrayOf(10)), StreamEntry(id11, byteArrayOf(11)))
            assertEquals(expected, tailReader.await())
            assertEquals(expected, service.read("events", ids.last()).toList())
            assertEquals(expected, service.read("events", ids.last().substringBefore('-')).toList())
            assertEquals(listOf(expected.last()), service.read("events", id10).toList())
            assertTrue(service.read("events", id11).toList().isEmpty())
            assertTrue(tail.toList().isEmpty())
            assertFailsWith<IllegalArgumentException> { service.read("events", "9-invalid").toList() }
        }
    }

    test("complete drains remaining entries for both slow and late readers") {
        runTest {
            val service = InMemoryStreamService()
            service.create("events")
            val first = service.append("events", byteArrayOf(1))
            val release = CompletableDeferred<Unit>()
            val received = mutableListOf<StreamEntry>()
            val reader = async(start = CoroutineStart.UNDISPATCHED) {
                service.read("events").collect {
                    received += it
                    if (it.id == first) release.await()
                }
            }
            val second = service.append("events", byteArrayOf(2))
            service.complete("events")
            assertEquals(listOf(StreamEntry(first, byteArrayOf(1))), received)
            release.complete(Unit)
            reader.await()
            val expected = listOf(StreamEntry(first, byteArrayOf(1)), StreamEntry(second, byteArrayOf(2)))
            assertEquals(expected, received)
            assertEquals(expected, service.read("events").toList())
        }
    }

    test("delete wakes idle readers and stops slow readers without draining or following a new stream") {
        runTest {
            val service = InMemoryStreamService()
            val first = service.append("events", byteArrayOf(1))
            val second = service.append("events", byteArrayOf(2))
            val release = CompletableDeferred<Unit>()
            val received = mutableListOf<StreamEntry>()
            val slow = async(start = CoroutineStart.UNDISPATCHED) {
                service.read("events").collect {
                    received += it
                    release.await()
                }
            }
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { service.read("events", second).toList() }
            service.delete("events")
            service.delete("events")
            assertTrue(waiting.await().isEmpty())
            assertFalse(service.exists("events"))
            assertNull(service.latest("events"))
            val missing = assertFailsWith<StreamNotFoundException> { service.read("events").toList() }
            assertEquals("events", missing.key)

            val third = service.append("events", byteArrayOf(3))
            service.complete("events")
            release.complete(Unit)
            slow.await()
            assertEquals(listOf(StreamEntry(first, byteArrayOf(1))), received)
            assertEquals(listOf(StreamEntry(third, byteArrayOf(3))), service.read("events").toList())
        }
    }

    test("create resets the old generation and a cancelled reader does not affect other readers") {
        runTest {
            val service = InMemoryStreamService()
            service.append("events", byteArrayOf(1))
            val reset = async(start = CoroutineStart.UNDISPATCHED) { service.read("events", "$").toList() }
            service.create("events")
            assertTrue(reset.await().isEmpty())
            assertNull(service.latest("events"))
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) { service.read("events").toList() }
            val reader = async(start = CoroutineStart.UNDISPATCHED) { service.read("events").toList() }
            cancelled.cancelAndJoin()
            val id = service.append("events", byteArrayOf(2))
            runCurrent()
            assertFalse(reader.isCompleted)
            service.complete("events")
            assertEquals(listOf(StreamEntry(id, byteArrayOf(2))), reader.await())
        }
    }

    test("concurrent append assigns unique ordered IDs and publishes every entry") {
        runTest {
            val service = InMemoryStreamService()
            val ids = withContext(Dispatchers.Default) {
                (1..32).map { value -> async { service.append("events", byteArrayOf(value.toByte())) } }.awaitAll()
            }
            service.complete("events")
            val replay = service.read("events").toList()
            assertEquals(ids.toSet(), replay.map { it.id }.toSet())
            assertEquals(32, replay.size)
            assertTrue(replay.zipWithNext().all { (a, b) ->
                a.id.substringBefore('-').toULong() < b.id.substringBefore('-').toULong()
            })
            assertContentEquals(
                ByteArray(32) { (it + 1).toByte() },
                replay.map { it.payload.single() }.sorted().toByteArray(),
            )
        }
    }
}
