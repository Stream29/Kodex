package io.github.stream29.kodex.utils.rpcexception

import kotlinx.serialization.Serializable

/** The exact AgentId has no persisted target. */
@Serializable
public class AgentNotFound : RemoteException()

/** The exact AgentId needs a live owner, or the captured owner has retired. */
@Serializable
public class AgentNotActive : RemoteException()

/** Deletion was rejected without stopping the Session's running children. */
@Serializable
public class SessionHasRunningSubagents : RemoteException()
