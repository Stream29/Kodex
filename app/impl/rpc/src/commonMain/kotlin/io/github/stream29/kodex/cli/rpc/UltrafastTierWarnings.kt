package io.github.stream29.kodex.cli.rpc

import io.github.stream29.kodex.agentstorage.contract.TokenCountKind
import io.github.stream29.kodex.agentstorage.contract.TokenCountSnapshot
import io.github.stream29.kodex.openai.ServiceTier

/**
 * Binding-local cursor, not a response log. Historical/rewound records establish
 * a baseline; only a newly observed forward token-count record can warn.
 * Missing usage records remain missing and intermediate conflated records are
 * not reconstructed. Storage index/nonce identify a record; response id also
 * suppresses adjacent duplicate reports, without retaining full history.
 */
internal class UltrafastTierWarnings(
    private var nonce: Long,
    private var highWaterIndex: Int,
    private var lastResponseId: String?,
) {
    fun observe(index: Int, cacheNonce: Long, snapshot: TokenCountSnapshot?): String? {
        if (cacheNonce != nonce) {
            nonce = cacheNonce
            highWaterIndex = index
            lastResponseId = snapshot?.diagnostics?.responseId
            return null
        }
        if (index <= highWaterIndex) return null
        highWaterIndex = index
        if (snapshot?.kind != TokenCountKind.Response) return null
        val diagnostics = snapshot.diagnostics ?: return null
        val responseId = diagnostics.responseId
        if (responseId != null && responseId == lastResponseId) return null
        lastResponseId = responseId
        val actual = diagnostics.serviceTier?.takeIf { it.isNotBlank() } ?: return null
        if (diagnostics.requestedServiceTier != ServiceTier.Ultrafast.requestValue ||
            actual == ServiceTier.Ultrafast.requestValue) return null
        return buildString {
            append("Requested ultrafast, but the response reported service tier '")
            append(actual)
            append("'.")
            diagnostics.requestedModel?.let { append(" Requested model: $it.") }
            responseId?.let { append(" Response: $it.") }
            append(" The saved selection was not changed.")
        }
    }
}
