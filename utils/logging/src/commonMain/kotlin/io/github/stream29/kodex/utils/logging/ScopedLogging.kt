package io.github.stream29.kodex.utils.logging

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KLoggingEventBuilder
import io.github.oshai.kotlinlogging.Level
import io.github.oshai.kotlinlogging.Marker

/**
 * Returns this logger with process-global context attached to every
 * event.
 */
public fun KLogger.global(): KLogger {
    return scoped(
        mapOf(
            ScopeField to GlobalScope
        )
    )
}

/**
 * Returns a Session-scoped logger. Any previous scope is replaced by this
 * Session identity.
 */
public fun KLogger.session(uri: String): KLogger {
    return scoped(
        mapOf(
            ScopeField to SessionScope,
            UriField to uri
        )
    )
}

/**
 * Returns an Agent-scoped logger, inheriting the Session identity already
 * attached to this logger when present.
 */
public fun KLogger.agent(): KLogger {
    return scoped(
        mapOf(
            ScopeField to AgentScope,
        )
    )
}

/**
 * Returns a Tool-scoped logger, inheriting the Session and Agent identities
 * already attached to this logger when present.
 */
public fun KLogger.tool(toolName: String, callId: String): KLogger {
    return scoped(
        mapOf(
            ScopeField to ToolScope,
            ToolNameField to toolName,
            CallIdField to callId
        )
    )
}

private fun KLogger.scoped(contextPayload: Map<String, Any?>): KLogger {
    return if (this is ScopedKLogger) {
        ScopedKLogger(
            delegate = delegate,
            contextPayload = this.contextPayload + contextPayload,
        )
    } else {
        ScopedKLogger(
            delegate = this,
            contextPayload = contextPayload,
        )
    }
}

private class ScopedKLogger(
    val delegate: KLogger,
    val contextPayload: Map<String, Any?>,
) : KLogger {
    override val name: String
        get() = delegate.name

    override fun isLoggingEnabledFor(level: Level, marker: Marker?): Boolean =
        delegate.isLoggingEnabledFor(level, marker)

    override fun at(
        level: Level,
        marker: Marker?,
        block: KLoggingEventBuilder.() -> Unit,
    ) {
        delegate.at(level, marker) {
            payload = contextPayload
            block()
        }
    }
}

private const val GlobalScope: String = "global"
private const val SessionScope: String = "session"
private const val AgentScope: String = "agent"
private const val ToolScope: String = "tool"
private const val ScopeField: String = "scope"
private const val UriField: String = "uri"
private const val ToolNameField: String = "tool_name"
private const val CallIdField: String = "call_id"
