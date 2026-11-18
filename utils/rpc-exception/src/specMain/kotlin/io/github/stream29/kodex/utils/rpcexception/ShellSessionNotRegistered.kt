package io.github.stream29.kodex.utils.rpcexception

import kotlinx.serialization.Serializable

/** The process registration is missing or no longer matches its captured nonce. */
@Serializable
public class ShellSessionNotRegistered : RemoteException()
