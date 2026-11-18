package io.github.stream29.kodex.utils.rpcexception

import kotlinx.serialization.Serializable

/** The requested answer was rejected before it could be persisted. */
@Serializable
public class AskAnswerRejected(public val alreadyAnswered: Boolean) : RemoteException()
