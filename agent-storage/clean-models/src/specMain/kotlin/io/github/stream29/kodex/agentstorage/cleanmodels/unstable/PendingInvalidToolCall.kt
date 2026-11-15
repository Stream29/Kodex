package io.github.stream29.kodex.agentstorage.cleanmodels.unstable

import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Pending call whose input was rejected during parsing.
 *
 * The tool runtime completes this call without invoking the requested tool and
 * moves the corresponding failure into [io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableInvalidToolCall].
 */
@Serializable
@SerialName("invalid_tool_call")
public data class PendingInvalidToolCall(
    @SerialName("call_id")
    override val callId: String,
    @SerialName("item_id")
    override val itemId: ResponseItemId? = null,
    public val invocation: PendingInvalidToolInvocation,
    public val message: String,
) : PendingToolEvent {
    override val toolName: String
        get() = when (val invalidInvocation = invocation) {
            is PendingInvalidToolInvocation.Function -> invalidInvocation.name
            is PendingInvalidToolInvocation.Custom -> invalidInvocation.name
        }
    override val toolNamespace: String?
        get() = when (val invalidInvocation = invocation) {
            is PendingInvalidToolInvocation.Function -> invalidInvocation.namespace
            is PendingInvalidToolInvocation.Custom -> invalidInvocation.namespace
        }

    override fun toResponseHistoryItems(): List<ResponseItem.HistoryItem> =
        listOf(
            when (val invalidInvocation = invocation) {
                is PendingInvalidToolInvocation.Function ->
                    ResponseItem.FunctionCall(
                        id = itemId,
                        callId = callId,
                        name = invalidInvocation.name,
                        namespace = invalidInvocation.namespace,
                        arguments = invalidInvocation.arguments,
                    )

                is PendingInvalidToolInvocation.Custom ->
                    ResponseItem.CustomToolCall(
                        id = itemId,
                        callId = callId,
                        name = invalidInvocation.name,
                        namespace = invalidInvocation.namespace,
                        input = invalidInvocation.input,
                    )
            },
        )
}

/**
 * Protocol-specific input that could not be converted to a typed pending call.
 */
@Serializable
public sealed interface PendingInvalidToolInvocation {
    /** Function arguments retained as their original string. */
    @Serializable
    @SerialName("function")
    public data class Function(
        public val name: String,
        public val namespace: String? = null,
        public val arguments: String,
    ) : PendingInvalidToolInvocation

    /** Custom-tool input retained as its original freeform string. */
    @Serializable
    @SerialName("custom")
    public data class Custom(
        public val name: String,
        public val namespace: String? = null,
        public val input: String,
    ) : PendingInvalidToolInvocation
}
