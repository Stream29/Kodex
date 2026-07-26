package io.github.stream29.codex.lite.hook.contract.approval

import io.github.stream29.codex.lite.hook.contract.tool.HookToolInvocation

/** Input inspected only after normal policy has requested approval. */
public data class PermissionRequest(
    public val invocation: HookToolInvocation,
)

/** Hook verdict passed to the remaining approval pipeline. */
public sealed interface PermissionRequestResult {
    public data object NoDecision : PermissionRequestResult

    public data object Allow : PermissionRequestResult

    public data class Deny(public val message: String) : PermissionRequestResult
}
