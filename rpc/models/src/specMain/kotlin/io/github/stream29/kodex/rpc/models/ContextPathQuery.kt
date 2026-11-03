package io.github.stream29.kodex.rpc.models

import kotlinx.serialization.Serializable

/**
 * Evaluate a candidate and the frozen original list together in the backend environment.
 * Inputs retain their spelling; the caller owns trimming new input, duplicate selection, merging
 * and CAS. [launchBaseDirectory] is the captured absolute CLI/welcome base, not daemon Home.
 * Relative custom roots remain invalid; supplying a base does not introduce relative-root support.
 * This read-only query never opens source files, rewrites old entries or admits a settings write.
 */
@Serializable
public data class ContextPathQuery(
    public val candidate: String,
    public val originalPaths: List<String>,
    public val launchBaseDirectory: String,
)
