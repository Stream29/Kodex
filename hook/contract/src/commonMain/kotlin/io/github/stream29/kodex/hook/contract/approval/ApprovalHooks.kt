package io.github.stream29.kodex.hook.contract.approval

/** Hook port reserved for a future approval runtime. */
public interface ApprovalHooks {
    public suspend fun onPermissionRequest(request: PermissionRequest): PermissionRequestResult
}

/** Approval-hook implementation that defers to normal policy. */
public data object NoOpApprovalHooks : ApprovalHooks {
    override suspend fun onPermissionRequest(request: PermissionRequest): PermissionRequestResult =
        PermissionRequestResult.NoDecision
}
