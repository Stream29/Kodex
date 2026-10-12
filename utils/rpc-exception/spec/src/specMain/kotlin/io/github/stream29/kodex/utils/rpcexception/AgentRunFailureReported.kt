package io.github.stream29.kodex.utils.rpcexception

import kotlinx.serialization.Serializable

/**
 * An accepted Agent run failed and the backend offered its UnhandledError Stop notification.
 *
 * This is still a failed RPC, not successful completion. The frontend must not report it again
 * through its local error Hook. Notification delivery remains live and best-effort; this value
 * does not acknowledge receipt. Rejection before execution and transport errors do not use it.
 */
@Serializable
public class AgentRunFailureReported(public val diagnostic: String?) : RemoteException()
