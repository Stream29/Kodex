package io.github.stream29.kodex.utils.mq

import kotlinx.coroutines.flow.Flow

/** Default per-subscriber ring capacity for exclusive topic subscriptions. */
public const val DefaultMqTopicBufferCapacity: Int = 256

/** Standard message header keys aligned with RabbitMQ / AMQP 0-9-1 properties. */
public object MqHeaders {
    public const val CorrelationId: String = "correlation_id"
    public const val ReplyTo: String = "reply_to"
    public const val ContentType: String = "content_type"
    public const val OriginId: String = "origin_id"
}

/** One raw message routed through an [MqBroker] exchange. */
public data class MqMessage(
    public val exchange: String,
    public val routingKey: String,
    public val body: ByteArray,
    public val headers: Map<String, String> = emptyMap(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MqMessage) return false
        return exchange == other.exchange &&
            routingKey == other.routingKey &&
            body.contentEquals(other.body) &&
            headers == other.headers
    }

    override fun hashCode(): Int {
        var result = exchange.hashCode()
        result = 31 * result + routingKey.hashCode()
        result = 31 * result + body.contentHashCode()
        result = 31 * result + headers.hashCode()
        return result
    }
}

/** Why an exclusive topic subscriber must re-read authoritative state from durable storage. */
public enum class MqResyncReason {
    /** The subscriber's bounded queue overflowed and dropped at least one older message. */
    Overflow,

    /** The underlying remote broker reconnected and recreated its exclusive auto-delete queue. */
    BrokerReconnected,
}

/** One element emitted by [MqBroker.subscribeTopic]. */
public sealed interface MqTopicSignal {
    public data class Message(public val message: MqMessage) : MqTopicSignal

    public data class ResyncRequired(public val reason: MqResyncReason) : MqTopicSignal
}

/**
 * One manually-acknowledged delivery from a named competing-consumer queue.
 *
 * Consumers must call either [ack] or [nack] at most once; repeated calls are no-ops.
 * Any delivery left unsettled when the consuming collector cancels is automatically requeued.
 */
public interface MqDelivery {
    public val message: MqMessage

    public suspend fun ack()

    public suspend fun nack(requeue: Boolean = true)
}

/**
 * RabbitMQ-aligned message broker SPI supporting topic-exchange broadcast subscriptions and
 * named competing-consumer queues with manual acknowledgment and Direct Reply-To.
 *
 * Implementations may run in-process ([InMemoryMqBroker]) or adapt an external broker such as
 * RabbitMQ over AMQP 0-9-1 without changing callers. Publishing takes a snapshot of the payload
 * and headers; changes made by one recipient do not affect any other delivery or redelivery.
 */
public interface MqBroker {
    /**
     * Idempotently declares a named queue. The default exchange routes to it by exact queue name,
     * including when no consumer is attached.
     */
    public fun declareQueue(queue: String)

    /**
     * Publishes [body] to [exchange] under [routingKey]. An empty exchange name selects the
     * default exchange. Unroutable messages are discarded (AMQP non-mandatory publish).
     */
    public suspend fun publish(
        exchange: String,
        routingKey: String,
        body: ByteArray,
        headers: Map<String, String> = emptyMap(),
    )

    /**
     * Non-suspending best-effort publish. Returns whether publication was accepted, not whether
     * anyone consumed the message. For the default exchange, returns `false` for an absent
     * queue or unavailable reply route.
     */
    public fun tryPublish(
        exchange: String,
        routingKey: String,
        body: ByteArray,
        headers: Map<String, String> = emptyMap(),
    ): Boolean

    /**
     * Opens an exclusive auto-delete topic subscription bound to [exchange] with [bindingKeys].
     *
     * Every active subscriber receives its own copy of each matching message (auto-ack). If the
     * subscriber cannot keep up and overflows [bufferCapacity], or if a remote broker reconnects,
     * [MqTopicSignal.ResyncRequired] is emitted before subsequent messages.
     */
    public fun subscribeTopic(
        exchange: String,
        bindingKeys: Set<String>,
        bufferCapacity: Int = DefaultMqTopicBufferCapacity,
    ): Flow<MqTopicSignal>

    /**
     * Consumes deliveries from a previously declared named [queue].
     *
     * Multiple collectors sharing [queue] act as competing consumers: each message is delivered
     * to one consumer at a time, bounded by [prefetch] unacknowledged deliveries per consumer
     * (`0` means unlimited). Unsettled deliveries are requeued when collection ends.
     *
     * @throws IllegalStateException If [queue] is not declared when collection begins.
     * @throws IllegalArgumentException If [prefetch] is negative.
     */
    public fun consumeQueue(queue: String, prefetch: Int = 1): Flow<MqDelivery>

    /**
     * Publishes a request to [queue] and suspends for one Direct Reply-To response.
     * The broker replaces [MqHeaders.ReplyTo] with an opaque, one-use reply address and assigns a
     * [MqHeaders.CorrelationId] if the caller did not supply one. Cancelling the wait discards
     * the reply route but does not withdraw a request already published.
     *
     * @throws IllegalStateException If [queue] has not been declared.
     */
    public suspend fun requestQueue(
        queue: String,
        body: ByteArray,
        headers: Map<String, String> = emptyMap(),
    ): MqMessage

    /**
     * Sends a response to an opaque [replyTo] address obtained from a request, without a reply
     * exchange or queue declaration. An omitted [MqHeaders.CorrelationId] inherits the request's
     * value. Returns `false` for unknown, used, or cancelled addresses, or a mismatched
     * correlation ID.
     */
    public fun replyTo(
        replyTo: String,
        body: ByteArray,
        headers: Map<String, String> = emptyMap(),
    ): Boolean
}

/**
 * Matches [routingKey] against an AMQP 0-9-1 topic [bindingKey].
 *
 * Words are separated by `.`. `*` matches exactly one word; `#` matches zero or more words.
 */
public expect fun matchTopicBinding(bindingKey: String, routingKey: String): Boolean

/** Creates a process-local, lock-free [MqBroker] implementing RabbitMQ topic and queue semantics. */
public expect fun InMemoryMqBroker(): MqBroker
