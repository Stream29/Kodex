package io.github.stream29.kodex.rpc.models

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingSuggestSubagentTaskToolEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Backend-produced events for frontend-local notification Hooks.
 *
 * These are transient observations, not current state, replayable history, or control requests.
 * Hook results do not affect backend execution. No event ID, acknowledgement or cache nonce
 * is introduced; publication and frontend Hook execution are not implemented here.
 */
@Serializable
public sealed interface Notification {
    /** A persisted root Agent stopped with an assistant message, host interaction, or final error. */
    @Serializable
    public sealed interface Stop : Notification {
        public val sessionIndex: Int

        /** The assistant message responsible for this stop, not an arbitrary historical message. */
        @Serializable
        @SerialName("stop_assistant_message")
        public data class AssistantMessage(
            override val sessionIndex: Int,
            public val message: StableAssistantMessage,
        ) : Stop

        /**
         * All pending user-input calls at this stop, in their original order.
         * Questions within a call remain grouped in its original arguments.
         */
        @Serializable
        @SerialName("stop_request_user_input")
        public data class RequestUserInput(
            override val sessionIndex: Int,
            public val requests: List<PendingRequestUserInputToolEvent>,
        ) : Stop {
            init {
                require(requests.isNotEmpty()) { "RequestUserInput notification requires pending calls." }
            }
        }

        /** All pending subagent-suggestion calls at this stop, in their original order. */
        @Serializable
        @SerialName("stop_suggest_subagent")
        public data class SuggestSubagent(
            override val sessionIndex: Int,
            public val requests: List<PendingSuggestSubagentTaskToolEvent>,
        ) : Stop {
            init {
                require(requests.isNotEmpty()) { "SuggestSubagent notification requires pending calls." }
            }
        }

        /**
         * A non-cancellation error that finally terminated accepted Agent execution.
         *
         * Internal retries, ordinary tool failure results, auxiliary title failures and unrelated
         * global operations are not Agent stops. Original failures remain in the backend log
         * and the applicable RPC failure channel.
         *
         * [message] is diagnostic text, possibly absent, not an encoded RemoteException to restore.
         */
        @Serializable
        @SerialName("stop_unhandled_error")
        public data class UnhandledError(
            override val sessionIndex: Int,
            public val message: String?,
        ) : Stop
    }
}
