package io.github.stream29.kodex.utils.rpcexception

import kotlinx.serialization.Serializable

/**
 * The cache nonce supplied to a timeline query or history command no longer matches.
 *
 * Replacement cache nonces arrive through the metadata subscription, not this exception.
 * Reads may be reconsidered after cache invalidation; destructive or creating commands
 * must not be automatically rebound to a replacement cache nonce and retried.
 * An inactive Session uses SessionNotActive; an unavailable or replaced current output
 * flow uses NoMatchException. This exception supplies neither replacement metadata nor
 * a generic failure branch for those other conditions.
 */
@Serializable
public class CacheNonceMismatch : RemoteException()
