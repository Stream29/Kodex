package io.github.stream29.kodex.agenteventbus

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.utils.mq.DefaultMqTopicBufferCapacity
import io.github.stream29.kodex.utils.mq.InMemoryMqBroker
import io.github.stream29.kodex.utils.mq.MqBroker
import io.github.stream29.kodex.utils.mq.MqHeaders
import io.github.stream29.kodex.utils.mq.MqMessage
import io.github.stream29.kodex.utils.mq.MqResyncReason
import io.github.stream29.kodex.utils.mq.MqTopicSignal
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

val backendSignalsTest by testSuite(testConfig = TestConfig.testScope(isEnabled = false)) {
    test("invalidations fan out with empty bodies and preserve routing keys and headers") {
        runTest {
            val broker = InMemoryMqBroker()
            val headers = mapOf(MqHeaders.OriginId to "fixture")
            val routingKey = BackendSignalKeys.agentTimeline(42)
            val exact = async(start = CoroutineStart.UNDISPATCHED) {
                broker.subscribeSignals(setOf(routingKey), DefaultMqTopicBufferCapacity).take(1).toList()
            }
            val wildcard = async(start = CoroutineStart.UNDISPATCHED) {
                broker.subscribeSignals(setOf(BackendSignalKeys.AllAgentTimelines), DefaultMqTopicBufferCapacity).take(2).toList()
            }
            val raw = async(start = CoroutineStart.UNDISPATCHED) {
                broker.subscribeTopic("kodex.broadcast", setOf(routingKey), DefaultMqTopicBufferCapacity).take(2).toList()
            }

            broker.publish("kodex.broadcast", routingKey, byteArrayOf(1))
            broker.publishSignal(BackendSignalKeys.agentRunning(42))
            broker.publishSignal(routingKey, headers)
            broker.publishSignal(BackendSignalKeys.agentTimeline(7))

            val matched = SignalDelivery.Signal("agent.42.timeline", headers)
            assertEquals(listOf(matched), exact.await())
            assertEquals(listOf(matched, SignalDelivery.Signal("agent.7.timeline", emptyMap())), wildcard.await())
            assertEquals(
                MqTopicSignal.Message(MqMessage("kodex.broadcast", "agent.42.timeline", byteArrayOf(), headers)),
                raw.await().last(),
            )
        }
    }

    test("invalidations report overflow before the latest retained message") {
        runTest {
            val broker = InMemoryMqBroker()
            val deliveries = async(start = CoroutineStart.UNDISPATCHED) {
                broker.subscribeSignals(setOf("global.#"), bufferCapacity = 1).take(2).toList()
            }
            broker.publishSignal(BackendSignalKeys.GlobalSettingsBackend)
            broker.publishSignal(BackendSignalKeys.GlobalAccountUsage)
            assertEquals(
                listOf(
                    SignalDelivery.ResyncRequired(MqResyncReason.Overflow),
                    SignalDelivery.Signal("global.account_usage", emptyMap()),
                ),
                deliveries.await(),
            )
        }
    }

    test("after-commit publishes distinct keys in order, never on rollback, and tolerates broker failure") {
        val attempted = mutableListOf<MqMessage>()
        var committed = false
        val broker = object : MqBroker by InMemoryMqBroker() {
            override fun tryPublish(exchange: String, routingKey: String, body: ByteArray, headers: Map<String, String>): Boolean {
                assertTrue(committed, "Publication must occur after commit")
                attempted += MqMessage(exchange, routingKey, body, headers)
                return when (routingKey) {
                    BackendSignalKeys.SessionGroups -> false
                    BackendSignalKeys.GlobalModels -> error("broker unavailable")
                    else -> true
                }
            }
        }
        assertFailsWith<IllegalStateException> {
            broker.publishAfterCommit { signals ->
                signals.add(BackendSignalKeys.Schedules)
                error("transaction rolled back")
            }
        }
        assertTrue(attempted.isEmpty())

        val headers = mapOf(MqHeaders.OriginId to "local")
        val count = broker.publishAfterCommit(headers) { signals ->
            signals.add(BackendSignalKeys.SessionGroups)
            signals.add(BackendSignalKeys.GlobalModels)
            signals.add(BackendSignalKeys.Schedules)
            signals.add(BackendSignalKeys.Schedules)
            assertTrue(attempted.isEmpty())
            committed = true
            42
        }
        assertEquals(42, count)
        assertEquals(
            listOf("session.groups", "global.models", "schedules").map {
                MqMessage("kodex.broadcast", it, byteArrayOf(), headers)
            },
            attempted,
        )
    }

    test("all three notifications use their own broadcast routes and preserve payloads and headers") {
        runTest {
            val broker = InMemoryMqBroker()
            val headers = mapOf(MqHeaders.OriginId to "fixture")
            val signals = listOf(
                AgentNotificationSignal.AskUserCreated(1, 2, 3),
                AgentNotificationSignal.StopAssistantMessage(1, 2, 4),
                AgentNotificationSignal.StopUnhandledError(1, 2, null),
            )
            val subscribed = async(start = CoroutineStart.UNDISPATCHED) {
                broker.subscribeNotifications(DefaultMqTopicBufferCapacity).take(3).toList()
            }
            val raw = async(start = CoroutineStart.UNDISPATCHED) {
                broker.subscribeTopic("kodex.broadcast", setOf("notification.#"), DefaultMqTopicBufferCapacity).take(4).toList()
            }

            broker.publish("kodex.broadcast", "notification.ask_user.created", byteArrayOf())
            broker.publishSignal(BackendSignalKeys.SessionGroups)
            for (signal in signals) broker.publishNotification(signal, headers)

            assertEquals(signals.map { NotificationDelivery.Signal(it, headers) }, subscribed.await())
            assertEquals(
                listOf("notification.ask_user.created", "notification.stop.assistant", "notification.stop.error"),
                raw.await().drop(1).map {
                    (it as MqTopicSignal.Message).message.also { message ->
                        assertTrue(message.body.isNotEmpty())
                    }.routingKey
                },
            )
        }
    }

    test("notification subscribers also receive overflow markers") {
        runTest {
            val broker = InMemoryMqBroker()
            val subscribed = async(start = CoroutineStart.UNDISPATCHED) {
                broker.subscribeNotifications(bufferCapacity = 1).take(2).toList()
            }
            val error = AgentNotificationSignal.StopUnhandledError(1, 2, "failure")
            broker.publishNotification(AgentNotificationSignal.AskUserCreated(1, 2, 3))
            broker.publishNotification(error)

            assertEquals(
                listOf(
                    NotificationDelivery.ResyncRequired(MqResyncReason.Overflow),
                    NotificationDelivery.Signal(error, emptyMap()),
                ),
                subscribed.await(),
            )
        }
    }
}
