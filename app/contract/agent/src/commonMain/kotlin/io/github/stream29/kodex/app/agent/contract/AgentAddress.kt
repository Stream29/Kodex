package io.github.stream29.kodex.app.agent.contract

/**
 * Globally unambiguous address of one Agent inside one persisted root Session.
 *
 * An Agent storage id is only unique inside its root Session, so callers must
 * retain both fields across asynchronous work.
 */
public data class AgentAddress(
    public val sessionIndex: Int,
    public val agentId: String,
) {
    init {
        require(sessionIndex >= 0) { "An Agent address must use a non-negative Session index." }
        require(agentId.isNotBlank()) { "An Agent address must use a non-blank Agent id." }
    }
}
