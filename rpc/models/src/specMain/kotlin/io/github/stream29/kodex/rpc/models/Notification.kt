package io.github.stream29.kodex.rpc.models

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AskUserEntry
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
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
    /**
     * Emitted immediately when a new unanswered [AskUserEntry] is appended to [sessionIndex]'s
     * `AskUserSnapshot` via `AgentRuntimeRpc.createAskUser` (`kodex ask create`), without waiting
     * for the Agent turn to stop.
     */
    @Serializable
    @SerialName("ask_user_created")
    public data class AskUserCreated(
        public val sessionIndex: Int,
        public val entry: AskUserEntry,
    ) : Notification

    /** A persisted root Agent stopped with an assistant message or final error. */
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
