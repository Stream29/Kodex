package io.github.stream29.kodex.agenteventbus

import io.github.stream29.kodex.utils.mq.MqBroker
import io.github.stream29.kodex.utils.mq.MqTopicSignal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.SerializationException

public actual suspend fun MqBroker.publishNotification(signal: AgentNotificationSignal, headers: Map<String, String>) {
    val body = EventBusJson.encodeToString(AgentNotificationSignal.serializer(), signal).encodeToByteArray()
    publish(BackendMqExchanges.Broadcast, signal.routingKey, body, headers)
}

public actual fun MqBroker.subscribeNotifications(bufferCapacity: Int): Flow<NotificationDelivery> =
    subscribeTopic(BackendMqExchanges.Broadcast, setOf(BackendSignalKeys.AllNotifications), bufferCapacity)
        .mapNotNull { delivery ->
            when (delivery) {
                is MqTopicSignal.Message -> delivery.message.let { message ->
                    val signal = try {
                        EventBusJson.decodeFromString<AgentNotificationSignal>(message.body.decodeToString())
                    } catch (_: SerializationException) {
                        null
                    }
                    signal?.takeIf { it.routingKey == message.routingKey }?.let {
                        NotificationDelivery.Signal(it, message.headers)
                    }
                }
                is MqTopicSignal.ResyncRequired -> NotificationDelivery.ResyncRequired(delivery.reason)
            }
        }

private val AgentNotificationSignal.routingKey: String
    get() = when (this) {
        is AgentNotificationSignal.AskUserCreated -> BackendSignalKeys.NotificationAskUserCreated
        is AgentNotificationSignal.StopAssistantMessage -> BackendSignalKeys.NotificationStopAssistant
        is AgentNotificationSignal.StopUnhandledError -> BackendSignalKeys.NotificationStopError
    }
