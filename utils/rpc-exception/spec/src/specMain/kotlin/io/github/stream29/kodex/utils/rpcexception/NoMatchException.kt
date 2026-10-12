package io.github.stream29.kodex.utils.rpcexception

import kotlinx.serialization.Serializable

/** The requested current output flow is no longer active or its nonce does not match. */
@Serializable
public class NoMatchException : RemoteException()
