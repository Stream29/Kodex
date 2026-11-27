@file:OptIn(ExperimentalAtomicApi::class)

package io.github.stream29.kodex.utils.mq

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

public actual fun matchTopicBinding(bindingKey: String, routingKey: String): Boolean {
    val pattern = if (bindingKey.isEmpty()) emptyList() else bindingKey.split('.')
    val words = if (routingKey.isEmpty()) emptyList() else routingKey.split('.')
    val pLen = pattern.size
    val wLen = words.size
    var prev = BooleanArray(wLen + 1)
    prev[0] = true
    for (pIdx in 1..pLen) {
        val token = pattern[pIdx - 1]
        val curr = BooleanArray(wLen + 1)
        if (token == "#") {
            curr[0] = prev[0]
            for (wIdx in 1..wLen) {
                curr[wIdx] = prev[wIdx] || curr[wIdx - 1]
            }
        } else {
            for (wIdx in 1..wLen) {
                if (token == "*" || token == words[wIdx - 1]) {
                    curr[wIdx] = prev[wIdx - 1]
                }
            }
        }
        prev = curr
    }
    return prev[wLen]
}

public actual fun InMemoryMqBroker(): MqBroker = InMemoryMqBrokerImpl()

private class InMemoryMqBrokerImpl : MqBroker {
    private val topicSubscriptions = AtomicReference<List<TopicSubscription>>(emptyList())
    private val namedQueues = AtomicReference<Map<String, NamedQueue>>(emptyMap())
    private val pendingReplies = AtomicReference<Map<String, PendingReply>>(emptyMap())
    private val nextReplyId = AtomicLong(0)

    override fun declareQueue(queue: String) {
        getOrCreateQueue(queue)
    }

    override suspend fun publish(
        exchange: String,
        routingKey: String,
        body: ByteArray,
        headers: Map<String, String>,
    ) {
        tryPublish(exchange, routingKey, body, headers)
    }

    override fun tryPublish(
        exchange: String,
        routingKey: String,
        body: ByteArray,
        headers: Map<String, String>,
    ): Boolean {
        if (exchange.isEmpty()) {
            if (routingKey.startsWith(DirectReplyToPrefix)) return replyTo(routingKey, body, headers)
            val queue = namedQueues.load()[routingKey] ?: return false
            queue.enqueue(MqMessage(exchange, routingKey, body, headers).snapshot())
            return true
        }
        val message = MqMessage(exchange, routingKey, body, headers).snapshot()
        for (subscription in topicSubscriptions.load()) {
            if (subscription.matches(exchange, routingKey)) {
                subscription.enqueue(message)
            }
        }
        return true
    }

    override fun subscribeTopic(
        exchange: String,
        bindingKeys: Set<String>,
        bufferCapacity: Int,
    ): Flow<MqTopicSignal> {
        require(exchange.isNotEmpty()) { "Exchange name must not be empty." }
        require(bindingKeys.isNotEmpty()) { "At least one binding key is required." }
        require(bufferCapacity > 0) { "Buffer capacity must be positive, got $bufferCapacity." }
        val bindings = bindingKeys.toSet()
        return flow {
            val subscription = TopicSubscription(exchange, bindings, bufferCapacity)
            topicSubscriptions.update { it + subscription }
            try {
                while (true) {
                    val signal = subscription.tryPoll()
                    if (signal == null) subscription.awaitMessage() else emit(signal)
                }
            } finally {
                topicSubscriptions.update { list -> list.filterNot { it === subscription } }
                subscription.close()
            }
        }
    }

    override fun consumeQueue(queue: String, prefetch: Int): Flow<MqDelivery> {
        require(prefetch >= 0) { "Prefetch must not be negative, got $prefetch." }
        return flow {
            val named = requireQueue(queue)
            val permits = if (prefetch == 0) null else Semaphore(prefetch)
            val inFlight = AtomicReference<Set<InFlightDelivery>>(emptySet())
            try {
                while (true) {
                    permits?.acquire()
                    val message = named.receive()
                    lateinit var delivery: InFlightDelivery
                    delivery = InFlightDelivery(
                        original = message,
                        onSettle = { requeue ->
                            inFlight.update { it - delivery }
                            if (requeue) named.requeue(listOf(message))
                            permits?.release()
                        },
                    )
                    inFlight.update { it + delivery }
                    emit(delivery)
                }
            } finally {
                named.requeue(inFlight.exchange(emptySet()).mapNotNull { it.claimForRequeue() })
            }
        }
    }

    override suspend fun requestQueue(queue: String, body: ByteArray, headers: Map<String, String>): MqMessage {
        val context = currentCoroutineContext()
        context.ensureActive()
        val named = requireQueue(queue)
        val id = nextReplyId.addAndFetch(1).toString()
        val address = DirectReplyToPrefix + id
        val correlationId = headers[MqHeaders.CorrelationId] ?: id
        val response = CompletableDeferred<MqMessage>(context[Job])
        pendingReplies.update { it + (address to PendingReply(correlationId, response)) }
        try {
            val requestHeaders = headers + mapOf(MqHeaders.ReplyTo to address, MqHeaders.CorrelationId to correlationId)
            context.ensureActive()
            named.enqueue(MqMessage("", queue, body, requestHeaders).snapshot())
            return response.await()
        } finally {
            pendingReplies.update { it - address }
            response.cancel()
        }
    }

    override fun replyTo(replyTo: String, body: ByteArray, headers: Map<String, String>): Boolean {
        val pending = pendingReplies.load()[replyTo] ?: return false
        val correlationId = headers[MqHeaders.CorrelationId]
        if (correlationId != null && correlationId != pending.correlationId) return false
        return pending.response.complete(
            MqMessage("", replyTo, body.copyOf(), headers + (MqHeaders.CorrelationId to pending.correlationId)),
        )
    }

    private fun requireQueue(name: String): NamedQueue =
        checkNotNull(namedQueues.load()[name]) { "Queue is not declared: $name" }

    private fun getOrCreateQueue(name: String): NamedQueue {
        require(name.isNotEmpty() && !name.startsWith(DirectReplyToPrefix)) { "Invalid queue name: $name" }
        while (true) {
            val current = namedQueues.load()
            current[name]?.let { return it }
            val created = NamedQueue()
            if (namedQueues.compareAndSet(current, current + (name to created))) {
                return created
            }
        }
    }
}

private const val DirectReplyToPrefix = "amq.rabbitmq.reply-to."

private class PendingReply(val correlationId: String, val response: CompletableDeferred<MqMessage>)

private fun MqMessage.snapshot(): MqMessage = copy(body = body.copyOf(), headers = headers.toMap())

private data class TopicBufferState(
    val items: List<MqMessage> = emptyList(),
    val overflowed: Boolean = false,
)

private class TopicSubscription(
    private val exchange: String,
    private val bindingKeys: Set<String>,
    private val capacity: Int,
) {
    private val buffer = AtomicReference(TopicBufferState())
    private val wakeup = Channel<Unit>(Channel.CONFLATED)

    fun matches(targetExchange: String, routingKey: String): Boolean =
        exchange == targetExchange && bindingKeys.any { pattern -> matchTopicBinding(pattern, routingKey) }

    fun enqueue(message: MqMessage) {
        buffer.update { state ->
            if (state.items.size >= capacity) {
                val trimmed = if (capacity == 1) emptyList() else state.items.drop(state.items.size - capacity + 1)
                TopicBufferState(items = trimmed + message, overflowed = true)
            } else {
                state.copy(items = state.items + message)
            }
        }
        wakeup.trySend(Unit)
    }

    fun tryPoll(): MqTopicSignal? {
        while (true) {
            val current = buffer.load()
            if (current.overflowed) {
                if (buffer.compareAndSet(current, current.copy(overflowed = false))) {
                    return MqTopicSignal.ResyncRequired(MqResyncReason.Overflow)
                }
            } else {
                val message = current.items.firstOrNull() ?: return null
                if (buffer.compareAndSet(current, TopicBufferState(items = current.items.drop(1)))) {
                    return MqTopicSignal.Message(message.snapshot())
                }
            }
        }
    }

    suspend fun awaitMessage() {
        wakeup.receive()
    }

    fun close() {
        wakeup.close()
    }
}

private class NamedQueue {
    private val messages = AtomicReference(QueueBuffer())

    fun enqueue(message: MqMessage) {
        updateMessages { it + message }
    }

    fun requeue(redeliveries: List<MqMessage>) {
        if (redeliveries.isNotEmpty()) updateMessages { redeliveries + it }
    }

    suspend fun receive(): MqMessage {
        while (true) {
            val current = messages.load()
            val message = current.items.firstOrNull()
            if (message == null) {
                current.changed.await()
            } else if (messages.compareAndSet(current, current.copy(items = current.items.drop(1)))) {
                return message
            }
        }
    }

    private inline fun updateMessages(transform: (List<MqMessage>) -> List<MqMessage>) {
        while (true) {
            val current = messages.load()
            if (messages.compareAndSet(current, QueueBuffer(transform(current.items)))) {
                // All waiters see the change, even if another waiter is cancelled before polling.
                current.changed.complete(Unit)
                return
            }
        }
    }
}

private data class QueueBuffer(
    val items: List<MqMessage> = emptyList(),
    val changed: CompletableDeferred<Unit> = CompletableDeferred(),
)

private class InFlightDelivery(
    private val original: MqMessage,
    private val onSettle: (requeue: Boolean) -> Unit,
) : MqDelivery {
    override val message: MqMessage = original.snapshot()
    private val settled = AtomicBoolean(false)

    override suspend fun ack() {
        if (settled.compareAndSet(false, true)) {
            onSettle(false)
        }
    }

    override suspend fun nack(requeue: Boolean) {
        if (settled.compareAndSet(false, true)) {
            onSettle(requeue)
        }
    }

    fun claimForRequeue(): MqMessage? = original.takeIf { settled.compareAndSet(false, true) }
}

private inline fun <T> AtomicReference<T>.update(transform: (T) -> T): T {
    while (true) {
        val current = load()
        val updated = transform(current)
        if (compareAndSet(current, updated)) return updated
    }
}
