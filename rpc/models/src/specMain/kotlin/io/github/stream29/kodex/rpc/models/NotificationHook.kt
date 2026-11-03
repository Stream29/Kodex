package io.github.stream29.kodex.rpc.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A configured command receiving a complete notification as JSON on stdin.
 *
 * Matching any selected type invokes this Hook once per notification. Type order is
 * irrelevant; command order comes from the owning configuration's ordered hooks list.
 * Each notification captures that list once; later edits affect only later notifications.
 * Names must be unique within that list. Strings are preserved without trimming.
 * Backend Stop Hooks and frontend error Hooks execute on the backend. Frontend error
 * Hooks use the backend Home; this model neither executes commands nor authorizes an
 * execution request. Configuration still uses the existing settings RPC.
 * Output cannot block tools, inject context or continue an Agent turn.
 */
@Serializable
public data class NotificationHook(
    public val name: String,
    public val types: Set<NotificationHookType>,
    public val command: String,
) {
    init {
        require(name.isNotBlank()) { "Notification Hook name must not be blank." }
        require(types.isNotEmpty()) { "Notification Hook requires at least one notification type." }
        require(command.isNotBlank()) { "Notification Hook command must not be blank." }
    }
}

/** Explicit selections of current notification branches, without a wildcard. */
@Serializable
public enum class NotificationHookType {
    @SerialName("stop_assistant_message")
    StopAssistantMessage,

    @SerialName("stop_request_user_input")
    StopRequestUserInput,

    @SerialName("stop_suggest_subagent")
    StopSuggestSubagent,

    /** Matches Agent error Stops and frontend-local errors/warnings; stored value is retained. */
    @SerialName("stop_unhandled_error")
    StopUnhandledError,
}
