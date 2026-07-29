package io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Side responsible for a pending deferred-tool search. */
@Serializable
public enum class PendingToolSearchExecution {
    @SerialName("client")
    Client,

    @SerialName("server")
    Server,
}
