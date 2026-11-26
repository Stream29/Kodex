package io.github.stream29.kodex.utils.logging

import io.github.oshai.kotlinlogging.KLogger

/**
 * Returns this logger with process-global context attached to every
 * event.
 */
public expect fun KLogger.global(): KLogger

/**
 * Returns a Session-scoped logger. Any previous scope is replaced by this
 * Agent identity when present.
 */
public expect fun KLogger.session(agentId: Int?): KLogger

/**
 * Returns an Agent-scoped logger, inheriting the Session identity already
 * attached to this logger when present.
 */
public expect fun KLogger.agent(): KLogger

/**
 * Returns a Tool-scoped logger, inheriting the Session and Agent identities
 * already attached to this logger when present.
 */
public expect fun KLogger.tool(toolName: String, callId: String): KLogger
