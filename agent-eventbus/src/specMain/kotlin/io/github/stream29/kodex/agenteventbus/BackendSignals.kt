package io.github.stream29.kodex.agenteventbus

import io.github.stream29.kodex.utils.mq.MqBroker
import io.github.stream29.kodex.utils.mq.MqResyncReason
import kotlinx.coroutines.flow.Flow

public object BackendMqExchanges {
    public const val Broadcast: String = "kodex.broadcast"
}

public object BackendMqQueues {
    public const val AgentInbox: String = "kodex.agent.inbox"
}

/** Backend broadcast routing keys and subscription patterns. */
public object BackendSignalKeys {
    public const val SessionGroups: String = "session.groups"
    public const val AllAgentTimelines: String = "agent.*.timeline"
    public const val AllAgentRunning: String = "agent.*.running"
    public const val AllAgentAwaitDeadlines: String = "agent.*.await_deadline"
    public const val AllAgentDeleted: String = "agent.*.deleted"
    public const val Schedules: String = "schedules"
    public const val GlobalSettingsBackend: String = "global.settings.backend"
    public const val GlobalSettingsFrontend: String = "global.settings.frontend"
    public const val GlobalModels: String = "global.models"
    public const val GlobalMcpServers: String = "global.mcp_servers"
    public const val GlobalAuthentication: String = "global.authentication"
    public const val GlobalAccountUsage: String = "global.account_usage"
    public const val AllGlobalSettings: String = "global.settings.#"
    public const val NotificationAskUserCreated: String = "notification.ask_user.created"
    public const val NotificationStopAssistant: String = "notification.stop.assistant"
    public const val NotificationStopError: String = "notification.stop.error"
    public const val AllNotifications: String = "notification.#"

    public fun agentTimeline(agentId: Int): String = agentSignalKey(agentId, "timeline")
    public fun agentRunning(agentId: Int): String = agentSignalKey(agentId, "running")
    public fun agentAwaitDeadline(agentId: Int): String = agentSignalKey(agentId, "await_deadline")
    public fun agentDeleted(agentId: Int): String = agentSignalKey(agentId, "deleted")
}

internal expect fun agentSignalKey(agentId: Int, suffix: String): String

public sealed interface SignalDelivery {
    public data class Signal(
        public val routingKey: String,
        public val headers: Map<String, String>,
    ) : SignalDelivery

    public data class ResyncRequired(public val reason: MqResyncReason) : SignalDelivery
}

/** Publishes an invalidation with an empty body on [BackendMqExchanges.Broadcast]. */
public expect suspend fun MqBroker.publishSignal(routingKey: String, headers: Map<String, String> = emptyMap())

/** Subscribes to empty-body invalidations, preserving overflow and reconnect notices. */
public expect fun MqBroker.subscribeSignals(
    bindingKeys: Set<String>,
    bufferCapacity: Int,
): Flow<SignalDelivery>

/** Collects invalidation keys for the enclosing [publishAfterCommit] call. */
public fun interface AfterCommitSignals {
    public fun add(routingKey: String)
}

/**
 * Runs [commit], which must return only after the transaction has committed and throw on rollback.
 * After normal return, attempts to [MqBroker.tryPublish] each distinct collected key with an empty
 * body. A publication failure does not change the committed result or skip other keys.
 */
public expect fun <T> MqBroker.publishAfterCommit(
    headers: Map<String, String> = emptyMap(),
    commit: (AfterCommitSignals) -> T,
): T
