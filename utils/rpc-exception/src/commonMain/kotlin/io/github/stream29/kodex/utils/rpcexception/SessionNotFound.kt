package io.github.stream29.kodex.utils.rpcexception

import kotlinx.serialization.Serializable

/**
 * The backend confirmed that the addressed persisted Session does not exist.
 *
 * A missing live owner uses SessionNotActive instead; storage, permission and transport
 * failures are not evidence of absence. This exception describes the check's result,
 * not a guarantee that the Session index can never be reused.
 *
 * A failed keep-alive activation must not silently create a replacement Session or
 * keep retrying the old binding. Deletion retains its separate false-on-absence result.
 */
@Serializable
public class SessionNotFound : RemoteException()
