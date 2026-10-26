@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.local

/**
 * A local failure established against the exact unavailable transport, not a remote business error.
 * It does not prove whether an in-flight operation was accepted and never authorizes write replay.
 * Read owners may rebuild their binding after connectivity returns; owner cancellation is separate.
 */
public expect class BackendConnectionLost(message: String, cause: Throwable? = null) : IllegalStateException
