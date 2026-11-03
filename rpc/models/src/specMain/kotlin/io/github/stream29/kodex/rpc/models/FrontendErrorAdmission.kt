package io.github.stream29.kodex.rpc.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Admission to backend-owned work, not a guarantee that a Hook completed. */
@Serializable
public enum class FrontendErrorAdmission {
    @SerialName("admitted")
    Admitted,

    @SerialName("rejected_overload")
    RejectedOverload,
}
