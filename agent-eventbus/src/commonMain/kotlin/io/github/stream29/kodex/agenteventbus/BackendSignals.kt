package io.github.stream29.kodex.agenteventbus

import io.github.stream29.kodex.utils.mq.MqBroker
import io.github.stream29.kodex.utils.mq.MqTopicSignal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

internal actual fun agentSignalKey(agentId: Int, suffix: String): String = "agent.$agentId.$suffix"

public actual suspend fun MqBroker.publishSignal(routingKey: String, headers: Map<String, String>) {
    publish(BackendMqExchanges.Broadcast, routingKey, byteArrayOf(), headers)
}

public actual fun MqBroker.subscribeSignals(
    bindingKeys: Set<String>,
    bufferCapacity: Int,
): Flow<SignalDelivery> = subscribeTopic(BackendMqExchanges.Broadcast, bindingKeys, bufferCapacity)
    .mapNotNull { delivery ->
        when (delivery) {
            is MqTopicSignal.Message -> delivery.message.takeIf { it.body.isEmpty() }?.let {
                SignalDelivery.Signal(it.routingKey, it.headers)
            }
            is MqTopicSignal.ResyncRequired -> SignalDelivery.ResyncRequired(delivery.reason)
        }
    }

public actual fun <T> MqBroker.publishAfterCommit(
    headers: Map<String, String>,
    commit: (AfterCommitSignals) -> T,
): T {
    val keys = linkedSetOf<String>()
    val result = commit { keys.add(it) }
    for (key in keys) {
        try {
            tryPublish(BackendMqExchanges.Broadcast, key, byteArrayOf(), headers)
        } catch (_: Exception) {
            // The transaction has already committed; invalidation is best effort.
        }
    }
    return result
}
