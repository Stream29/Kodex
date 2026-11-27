package io.github.stream29.kodex.agenteventbus

import io.github.stream29.kodex.agentstorage.contract.ext.AskUserAlreadyAnswered
import io.github.stream29.kodex.agentstorage.contract.ext.AskUserEntryNotFound
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.utils.mq.MqBroker
import io.github.stream29.kodex.utils.mq.MqDelivery
import io.github.stream29.kodex.utils.mq.MqHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

@Serializable
internal sealed interface AgentInboxMessage {
    val targetAgentId: Int

    @Serializable
    @SerialName("user_message")
    data class UserMessage(override val targetAgentId: Int, val content: List<ContentItem>) : AgentInboxMessage

    @Serializable
    @SerialName("ask_user_answer")
    data class AskUserAnswer(
        override val targetAgentId: Int,
        val askId: Int,
        val response: RequestUserInputResponse,
    ) : AgentInboxMessage

    @Serializable
    @SerialName("schedule_trigger")
    data class ScheduleTrigger(
        override val targetAgentId: Int,
        val scheduleId: Long,
        val description: String,
    ) : AgentInboxMessage

    @Serializable
    @SerialName("await_deadline_trigger")
    data class AwaitDeadlineTrigger(
        override val targetAgentId: Int,
        val snapshotIndex: Int,
        val callId: String,
        val timeoutAt: Instant,
    ) : AgentInboxMessage

    @Serializable
    @SerialName("subagent_completion")
    data class SubagentCompletion(override val targetAgentId: Int, val childAgentId: Int) : AgentInboxMessage
}

internal val EventBusJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

internal class AgentInbox(private val broker: MqBroker) {
    init {
        broker.declareQueue(BackendMqQueues.AgentInbox)
    }

    suspend fun submit(message: AgentInboxMessage) {
        val reply = broker.requestQueue(
            BackendMqQueues.AgentInbox,
            EventBusJson.encodeToString(message).encodeToByteArray(),
            mapOf(MqHeaders.ContentType to "application/json"),
        )
        EventBusJson.decodeFromString<InboxReply>(reply.body.decodeToString()).throwOnFailure(message)
    }

    suspend fun consume(handle: suspend (AgentInboxMessage) -> Unit): Unit = coroutineScope {
        broker.consumeQueue(BackendMqQueues.AgentInbox, prefetch = 0).collect { delivery ->
            val message = try {
                EventBusJson.decodeFromString<AgentInboxMessage>(delivery.message.body.decodeToString(throwOnInvalidSequence = true))
            } catch (error: Exception) {
                delivery.settle(InboxReply.Failed(InboxFailure.InvalidArgument, error.message))
                return@collect
            }
            if (message is AgentInboxMessage.AwaitDeadlineTrigger) {
                // The check can join a full turn; keep accepting inbox work while it runs.
                launch(start = CoroutineStart.UNDISPATCHED) { deliver(delivery, message, handle) }
            } else {
                deliver(delivery, message, handle)
            }
        }
    }

    private suspend fun deliver(
        delivery: MqDelivery,
        message: AgentInboxMessage,
        handle: suspend (AgentInboxMessage) -> Unit,
    ) {
        val reply = try {
            handle(message)
            InboxReply.Ok
        } catch (error: Exception) {
            // A stopped consumer leaves the delivery unsettled so the broker can requeue it.
            currentCoroutineContext().ensureActive()
            InboxReply.Failed(
                failure = when (error) {
                    is AskUserEntryNotFound -> InboxFailure.AskUserEntryNotFound
                    is AskUserAlreadyAnswered -> InboxFailure.AskUserAlreadyAnswered
                    is IllegalArgumentException -> InboxFailure.InvalidArgument
                    is CancellationException -> InboxFailure.Cancelled
                    else -> InboxFailure.Failed
                },
                message = error.message,
            )
        }
        delivery.settle(reply)
    }

    private suspend fun MqDelivery.settle(reply: InboxReply) = withContext(NonCancellable) {
        // Replied failures are final: retrying the same invalid or partially applied command is unsafe.
        if (reply is InboxReply.Ok) ack() else nack(requeue = false)
        message.headers[MqHeaders.ReplyTo]?.let { replyTo ->
            val correlation = message.headers[MqHeaders.CorrelationId]
            broker.replyTo(
                replyTo,
                EventBusJson.encodeToString(reply).encodeToByteArray(),
                correlation?.let { mapOf(MqHeaders.CorrelationId to it) }.orEmpty(),
            )
        }
        Unit
    }
}

@Serializable
private sealed interface InboxReply {
    @Serializable
    @SerialName("ok")
    data object Ok : InboxReply

    @Serializable
    @SerialName("failed")
    data class Failed(
        val failure: InboxFailure,
        val message: String? = null,
    ) : InboxReply

    fun throwOnFailure(request: AgentInboxMessage) {
        if (this !is Failed) return
        when (failure) {
            InboxFailure.AskUserEntryNotFound -> throw AskUserEntryNotFound((request as AgentInboxMessage.AskUserAnswer).askId)
            InboxFailure.AskUserAlreadyAnswered -> throw AskUserAlreadyAnswered((request as AgentInboxMessage.AskUserAnswer).askId)
            InboxFailure.InvalidArgument -> throw IllegalArgumentException(message)
            InboxFailure.Cancelled -> throw CancellationException(message)
            InboxFailure.Failed -> throw IllegalStateException(message)
        }
    }
}

@Serializable
private enum class InboxFailure {
    AskUserEntryNotFound,
    AskUserAlreadyAnswered,
    InvalidArgument,
    Cancelled,
    Failed,
}
