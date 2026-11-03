package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.agentcontext.contract.ContextPathEvaluation
import io.github.stream29.kodex.app.contextsourcesettings.ContextSourcePathPolicy
import io.github.stream29.kodex.rpc.contract.GlobalRpc
import io.github.stream29.kodex.rpc.models.ContextPathQuery

/** Captures the connection and CLI/welcome base; the shared VM still owns spelling and merging. */
internal class RpcContextSourcePathPolicy(
    private val rpc: GlobalRpc,
    private val launchBaseDirectory: String,
) : ContextSourcePathPolicy {
    override suspend fun evaluate(candidate: String, originalPaths: List<String>): ContextPathEvaluation =
        rpc.evaluateContextPaths(ContextPathQuery(candidate, originalPaths.toList(), launchBaseDirectory))
}
