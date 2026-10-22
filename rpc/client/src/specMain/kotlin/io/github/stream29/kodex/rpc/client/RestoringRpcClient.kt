@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.client

import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient

/**
 * Restores known remote exceptions and isolates upstream RPC collection from downstream failures.
 *
 * Create service proxies from this client, not from [delegate]. Existing proxies are unaffected.
 * Streaming calls cover both immediate Flow creation failures and later upstream failures.
 * Unknown failures and downstream consumers' own exceptions are not reclassified.
 * If an upstream failure arrives after the subscriber was cancelled, its cancellation wins
 * before the buffer child can fail the subscriber's still-live owner.
 *
 * This adapter neither owns the connection nor retries calls, reconnects or restores state.
 * It does not start collecting a Flow until a caller collects the returned Flow.
 */
public expect class RestoringRpcClient(delegate: RpcClient) : RpcClient {
    override suspend fun <T> call(call: RpcCall): T
    override fun <T> callServerStreaming(call: RpcCall): Flow<T>
}
