package io.github.stream29.kodex.agenteventbus

import io.github.stream29.kodex.utils.mq.MqBroker
import io.github.stream29.kodex.utils.mq.MqResyncReason
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Transient notifications carrying only the fields needed to project a backend notification. */
@Serializable
public sealed interface AgentNotificationSignal {
    @Serializable
    @SerialName(BackendSignalKeys.NotificationAskUserCreated)
    public data class AskUserCreated(
        public val sessionIndex: Int,
        public val agentId: Int,
        public val askId: Int,
    ) : AgentNotificationSignal

    @Serializable
    @SerialName(BackendSignalKeys.NotificationStopAssistant)
    public data class StopAssistantMessage(
        public val sessionIndex: Int,
        public val agentId: Int,
        public val commitIndex: Int,
    ) : AgentNotificationSignal

    @Serializable
    @SerialName(BackendSignalKeys.NotificationStopError)
    public data class StopUnhandledError(
        public val sessionIndex: Int,
        public val agentId: Int,
        public val message: String?,
    ) : AgentNotificationSignal
}

public sealed interface NotificationDelivery {
    public data class Signal(
        public val signal: AgentNotificationSignal,
        public val headers: Map<String, String>,
    ) : NotificationDelivery

    public data class ResyncRequired(public val reason: MqResyncReason) : NotificationDelivery
}

/** Publishes [signal] as JSON on its notification routing key on [BackendMqExchanges.Broadcast]. */
public expect suspend fun MqBroker.publishNotification(
    signal: AgentNotificationSignal,
    headers: Map<String, String> = emptyMap(),
)

/** Receives transient notifications and broker resync markers, skipping invalid notification bodies. */
public expect fun MqBroker.subscribeNotifications(
    bufferCapacity: Int,
): Flow<NotificationDelivery>
