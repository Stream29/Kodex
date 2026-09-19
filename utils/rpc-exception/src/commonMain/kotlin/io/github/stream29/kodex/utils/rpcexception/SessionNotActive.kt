package io.github.stream29.kodex.utils.rpcexception

import kotlinx.serialization.Serializable

/**
 * An operation requires a live Session, or its subscription ended because the Session
 * became inactive. This is not evidence that the persisted Session has been deleted.
 *
 * The frontend must await successful keep-alive activation before accessing it again.
 * Restoring this exception does not activate the Session or retry the failed operation.
 */
@Serializable
public class SessionNotActive : RemoteException()
