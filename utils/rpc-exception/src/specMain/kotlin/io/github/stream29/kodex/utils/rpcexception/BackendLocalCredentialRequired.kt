package io.github.stream29.kodex.utils.rpcexception

import kotlinx.serialization.Serializable

/** This RPC requires the normal local backend credential, not a frontend browser grant. */
@Serializable
public class BackendLocalCredentialRequired : RemoteException()
