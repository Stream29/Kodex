package io.github.stream29.kodex.rpc.localserver

import kotlinx.serialization.json.JsonObject

/**
 * Host-side stable control-plane operations. JSON replies contain scalar lifecycle metadata,
 * never business settings, credentials or framework exception serialization.
 *
 * stop acceptance is not cleanup completion. The server calls [stopReplyFinished] after the
 * response-write boundary (including failure), allowing the host to cancel its service graph.
 * [reload] must use the backend's validation and resource coordination; it is not raw file reload.
 */
public interface BackendControl {
    public fun status(): JsonObject
    public suspend fun heartbeat(): Boolean
    public suspend fun acceptStop(): Boolean
    public fun stopReplyFinished()
    public suspend fun reload()
    public fun ready(): Boolean
}
