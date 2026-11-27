@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.utils.mq

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

val inMemoryMqBrokerTest by testSuite(
    testConfig = TestConfig.testScope(isEnabled = false),
) {
    test("matchTopicBinding follows AMQP 0-9-1 star and hash semantics") {
        assertTrue(matchTopicBinding("session.groups.invalidated", "session.groups.invalidated"))
        assertFalse(matchTopicBinding("session.groups.invalidated", "session.entries.invalidated"))

        assertTrue(matchTopicBinding("agent.*.timeline", "agent.42.timeline"))
        assertFalse(matchTopicBinding("agent.*.timeline", "agent.timeline"))
        assertFalse(matchTopicBinding("agent.*.timeline", "agent.42.sub.timeline"))

        assertTrue(matchTopicBinding("agent.#", "agent"))
        assertTrue(matchTopicBinding("agent.#", "agent.42"))
        assertTrue(matchTopicBinding("agent.#", "agent.42.timeline"))
        assertFalse(matchTopicBinding("agent.#", "session.42"))

        assertTrue(matchTopicBinding("#.timeline", "timeline"))
        assertTrue(matchTopicBinding("#.timeline", "agent.42.timeline"))
        assertTrue(matchTopicBinding("agent.#.timeline", "agent.timeline"))
        assertTrue(matchTopicBinding("agent.#.timeline", "agent.42.x.timeline"))

        assertTrue(matchTopicBinding("#", ""))
        assertTrue(matchTopicBinding("#", "a.b.c"))
        assertTrue(matchTopicBinding("", ""))
        assertFalse(matchTopicBinding("*", ""))
        assertTrue(matchTopicBinding("a.#.#.*", "a.b.c"))
        assertFalse(matchTopicBinding("a.#.#.*", "a"))
        assertFalse(matchTopicBinding("a.wh*", "a.what"))
    }

    test("subscribeTopic fans out matching events and isolates non-matching exchanges and keys") {
        runTest {
            val broker = InMemoryMqBroker()
            val allAgentEvents = mutableListOf<String>()
            val agent42Timeline = mutableListOf<String>()
            val otherExchange = mutableListOf<String>()

            val j1 = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                broker.subscribeTopic("kodex.events", setOf("agent.#"), DefaultMqTopicBufferCapacity).collect { signal ->
                    allAgentEvents += assertIs<MqTopicSignal.Message>(signal).message.routingKey
                }
            }
            val j2 = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                broker.subscribeTopic("kodex.events", setOf("agent.42.timeline"), DefaultMqTopicBufferCapacity).collect { signal ->
                    agent42Timeline += assertIs<MqTopicSignal.Message>(signal).message.routingKey
                }
            }
            val j3 = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                broker.subscribeTopic("kodex.other", setOf("#"), DefaultMqTopicBufferCapacity).collect { signal ->
                    otherExchange += assertIs<MqTopicSignal.Message>(signal).message.routingKey
                }
            }

            broker.publish("kodex.events", "agent.42.timeline", "t1".encodeToByteArray())
            broker.publish("kodex.events", "agent.99.running", "r1".encodeToByteArray())
            broker.publish("kodex.events", "schedule.invalidated", "s1".encodeToByteArray())
            broker.publish("kodex.other", "agent.42.timeline", "o1".encodeToByteArray())
            runCurrent()

            assertEquals(listOf("agent.42.timeline", "agent.99.running"), allAgentEvents)
            assertEquals(listOf("agent.42.timeline"), agent42Timeline)
            assertEquals(listOf("agent.42.timeline"), otherExchange)

            j1.cancelAndJoin()
            j2.cancelAndJoin()
            j3.cancelAndJoin()
        }
    }

    test("subscribeTopic emits ResyncRequired(Overflow) to slow subscriber when bounded buffer drops messages") {
        runTest {
            val broker = InMemoryMqBroker()
            val gate = CompletableDeferred<Unit>()
            val received = mutableListOf<MqTopicSignal>()

            val job = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                broker.subscribeTopic("kodex.events", setOf("session.#"), bufferCapacity = 2).collect { signal ->
                    received += signal
                    if (received.size == 1) gate.await()
                }
            }

            broker.tryPublish("kodex.events", "session.1", "m1".encodeToByteArray())
            broker.tryPublish("kodex.events", "session.2", "m2".encodeToByteArray())
            runCurrent()
            // m2 is still queued when delivery of m1 suspends. It must not bypass the resync.
            broker.tryPublish("kodex.events", "session.3", "m3".encodeToByteArray())
            broker.tryPublish("kodex.events", "session.4", "m4".encodeToByteArray())
            broker.tryPublish("kodex.events", "session.5", "m5".encodeToByteArray())

            gate.complete(Unit)
            runCurrent()

            assertEquals(4, received.size)
            assertEquals("session.1", assertIs<MqTopicSignal.Message>(received[0]).message.routingKey)
            assertEquals(MqResyncReason.Overflow, assertIs<MqTopicSignal.ResyncRequired>(received[1]).reason)
            assertEquals("session.4", assertIs<MqTopicSignal.Message>(received[2]).message.routingKey)
            assertEquals("session.5", assertIs<MqTopicSignal.Message>(received[3]).message.routingKey)

            job.cancelAndJoin()
        }
    }

    test("default exchange routes to exact declared names without topic fan-out") {
        runTest {
            val broker = InMemoryMqBroker()
            broker.declareQueue("work.*")
            broker.declareQueue("work.1")
            broker.declareQueue("work.1")
            assertFalse(broker.tryPublish("", "absent", byteArrayOf(0)))
            broker.publish("", "absent", byteArrayOf(0))
            assertFailsWith<IllegalStateException> { broker.consumeQueue("absent", prefetch = 1).first() }
            broker.declareQueue("absent")

            val topic = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                broker.subscribeTopic("topic", setOf("#"), DefaultMqTopicBufferCapacity).collect { error("Unexpected topic delivery: $it") }
            }
            val body = byteArrayOf(1)
            val headers = mutableMapOf(MqHeaders.ContentType to "application/octet-stream")
            broker.publish("", "work.1", body, headers)
            body[0] = 9
            headers.clear()
            assertTrue(broker.tryPublish("", "work.1", byteArrayOf(2)))
            broker.publish("", "work.*", byteArrayOf(3))

            val deliveries = mutableListOf<MqMessage>()
            broker.consumeQueue("work.1", prefetch = 1).take(2).collect {
                deliveries += it.message
                it.ack()
            }
            assertEquals(
                listOf(
                    MqMessage("", "work.1", byteArrayOf(1), mapOf(MqHeaders.ContentType to "application/octet-stream")),
                    MqMessage("", "work.1", byteArrayOf(2)),
                ),
                deliveries,
            )
            assertEquals(MqMessage("", "work.*", byteArrayOf(3)), broker.consumeQueue("work.*", prefetch = 1).first {
                it.ack()
                true
            }.message)
            val absent = backgroundScope.async { broker.consumeQueue("absent", prefetch = 1).first() }
            runCurrent()
            assertFalse(absent.isCompleted)
            topic.cancelAndJoin()
        }
    }

    test("consumeQueue distributes across competing consumers, honors ack/nack, and requeues on cancel") {
        runTest {
            val broker = InMemoryMqBroker()
            broker.declareQueue("work")

            // Publish before any consumer attaches to verify pre-declared queue buffering.
            broker.publish("", "work", "job-1".encodeToByteArray())
            broker.publish("", "work", "job-2".encodeToByteArray())

            val c1Hold = CompletableDeferred<Unit>()
            val seenByC1 = mutableListOf<String>()
            val seenByC2 = mutableListOf<String>()

            val consumer1 = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                broker.consumeQueue("work", prefetch = 1).collect { delivery ->
                    val body = delivery.message.body.decodeToString()
                    seenByC1 += body
                    if (body == "job-1") {
                        // Hold unacked until cancelled to verify auto-requeue on cancel.
                        c1Hold.await()
                    }
                    delivery.ack()
                }
            }
            val consumer2 = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                var nackedOnce = false
                broker.consumeQueue("work", prefetch = 1).collect { delivery ->
                    val body = delivery.message.body.decodeToString()
                    seenByC2 += body
                    if (body == "job-2" && !nackedOnce) {
                        nackedOnce = true
                        delivery.nack(requeue = true)
                    } else {
                        delivery.ack()
                    }
                }
            }

            runCurrent()
            // consumer1 took job-1 (unacked), consumer2 took job-2, nacked(requeue=true), then retook & acked job-2.
            assertEquals(listOf("job-1"), seenByC1)
            assertEquals(listOf("job-2", "job-2"), seenByC2)

            // Cancelling consumer1 must requeue unacked job-1 so consumer2 processes it.
            consumer1.cancelAndJoin()
            runCurrent()
            assertEquals(listOf("job-2", "job-2", "job-1"), seenByC2)

            // Verify nack(requeue = false) drops the message permanently.
            broker.declareQueue("dlq-test")
            val rejected = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                broker.consumeQueue("dlq-test", prefetch = 1).first {
                    it.nack(requeue = false)
                    it.nack(requeue = true)
                    true
                }
            }
            broker.publish("", "dlq-test", "poison".encodeToByteArray())
            runCurrent()
            assertContentEquals("poison".encodeToByteArray(), rejected.await().message.body)
            val leftover = backgroundScope.async { broker.consumeQueue("dlq-test", prefetch = 1).first() }
            runCurrent()
            assertFalse(leftover.isCompleted)

            consumer2.cancelAndJoin()
        }
    }

    test("prefetch bounds unsettled deliveries and cancellation restores original payloads in order") {
        runTest {
            val broker = InMemoryMqBroker()
            broker.declareQueue("work")
            for (id in 1..4) broker.publish("", "work", byteArrayOf(id.toByte()))
            val held = mutableListOf<MqDelivery>()
            val consumer = backgroundScope.launch {
                broker.consumeQueue("work", prefetch = 2).collect { held += it }
            }
            runCurrent()
            assertEquals(listOf<Byte>(1, 2), held.map { it.message.body.single() })
            held.first().ack()
            held.first().nack(requeue = true)
            runCurrent()
            assertEquals(listOf<Byte>(1, 2, 3), held.map { it.message.body.single() })
            held[1].message.body[0] = 99
            consumer.cancelAndJoin()

            val replay = broker.consumeQueue("work", prefetch = 0).take(3).toList()
            assertEquals(listOf<Byte>(2, 3, 4), replay.map { it.message.body.single() })
        }
    }

    test("cancelling a waiting competitor does not lose the wake-up for another consumer") {
        runTest {
            val broker = InMemoryMqBroker()
            broker.declareQueue("work")
            val first = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                broker.consumeQueue("work", prefetch = 1).first()
            }
            val second = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                broker.consumeQueue("work", prefetch = 1).first {
                    it.ack()
                    true
                }
            }
            broker.publish("", "work", byteArrayOf(1))
            first.cancelAndJoin()
            assertContentEquals(byteArrayOf(1), second.await().message.body)
        }
    }

    test("Direct Reply-To routes concurrent requests independently and accepts only one correlated reply") {
        runTest {
            val broker = InMemoryMqBroker()
            broker.declareQueue("work")
            assertFailsWith<IllegalStateException> { broker.requestQueue("absent", byteArrayOf()) }
            val headers = mapOf(MqHeaders.CorrelationId to "shared", MqHeaders.ReplyTo to "ignored")
            val first = async(start = CoroutineStart.UNDISPATCHED) { broker.requestQueue("work", byteArrayOf(1), headers) }
            val second = async(start = CoroutineStart.UNDISPATCHED) { broker.requestQueue("work", byteArrayOf(2), headers) }
            val requests = mutableListOf<MqMessage>()
            broker.consumeQueue("work", prefetch = 1).take(2).collect {
                requests += it.message
                it.ack()
            }
            assertEquals(listOf<Byte>(1, 2), requests.map { it.body.single() })
            assertTrue(requests.all { it.exchange == "" && it.routingKey == "work" })
            val (firstAddress, secondAddress) = requests.map { it.headers.getValue(MqHeaders.ReplyTo) }
            assertTrue(firstAddress != secondAddress && firstAddress != "ignored" && secondAddress != "ignored")
            assertFalse(broker.replyTo(firstAddress, byteArrayOf(9), mapOf(MqHeaders.CorrelationId to "wrong")))
            assertTrue(broker.replyTo(secondAddress, byteArrayOf(22)))
            assertFalse(broker.replyTo(secondAddress, byteArrayOf(9)))
            val body = byteArrayOf(11)
            assertTrue(broker.tryPublish("", firstAddress, body))
            body[0] = 9
            assertEquals(MqMessage("", firstAddress, byteArrayOf(11), mapOf(MqHeaders.CorrelationId to "shared")), first.await())
            assertEquals(MqMessage("", secondAddress, byteArrayOf(22), mapOf(MqHeaders.CorrelationId to "shared")), second.await())
        }
    }

    test("Direct Reply-To cancellation drops the reply route but not the queued request") {
        runTest {
            val broker = InMemoryMqBroker()
            broker.declareQueue("work")
            val requester = async(start = CoroutineStart.UNDISPATCHED) { broker.requestQueue("work", byteArrayOf(1)) }
            requester.cancelAndJoin()
            val request = broker.consumeQueue("work", prefetch = 1).first {
                it.ack()
                true
            }.message
            assertContentEquals(byteArrayOf(1), request.body)
            assertTrue(request.headers.getValue(MqHeaders.CorrelationId).isNotEmpty())
            assertFalse(broker.replyTo(request.headers.getValue(MqHeaders.ReplyTo), byteArrayOf(2)))
        }
    }
}
