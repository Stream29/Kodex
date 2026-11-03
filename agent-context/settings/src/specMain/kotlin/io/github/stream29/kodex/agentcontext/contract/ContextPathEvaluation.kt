package io.github.stream29.kodex.agentcontext.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Lexical comparison, not filesystem identity or an existence/readability check.
 * A null key means invalid syntax (blank, '$', unsupported relative spelling); matching built-ins
 * means one of the actual Agents/Kodex/Codex static roots used by this backend's Context resolver.
 * No case folding, realpath, symlink identity, directory scan or settings mutation is implied.
 */
@Serializable
public data class ContextPathComparison(
    public val lexicalKey: String?,
    public val matchesBuiltIn: Boolean,
) {
    init {
        require(lexicalKey != null || !matchesBuiltIn) { "Invalid paths cannot match built-in roots." }
    }
}

@Serializable
public enum class ContextPathEvaluationFailure { InvalidLaunchBase, EnvironmentUnavailable, LimitExceeded }

/** One environment snapshot, preserving original-list order and cardinality, including invalid rows. */
@Serializable
public sealed interface ContextPathEvaluation {
    @Serializable
    @SerialName("evaluated")
    public data class Evaluated(
        public val candidate: ContextPathComparison,
        public val originalPaths: List<ContextPathComparison>,
    ) : ContextPathEvaluation

    @Serializable
    @SerialName("failure")
    public data class Failure(public val reason: ContextPathEvaluationFailure) : ContextPathEvaluation
}
