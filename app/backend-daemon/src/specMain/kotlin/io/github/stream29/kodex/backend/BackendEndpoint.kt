package io.github.stream29.kodex.backend

import kotlinx.serialization.Serializable

/** Strict terminal wait contract checkpoint; business compatibility is independent of app releases. */
public const val BackendBusinessProtocol: String = "0.5.2"
public const val BackendControlProtocol: Int = 1

/**
 * Private metadata published by the holder of the kernel instance lock.
 * Presence alone is not proof that an instance is alive or ready.
 * Passwords are local connection credentials, never display or diagnostic fields.
 */
@Serializable
public class BackendEndpoint(
    public val instanceId: String,
    public val pid: Long,
    public val port: Int,
    public val password: String,
    public val applicationVersion: String,
    public val businessProtocol: String = BackendBusinessProtocol,
    public val controlProtocol: Int = BackendControlProtocol,
    /** Terminal diagnostic for this exact instance; not proof of liveness or a replayable event. */
    public val failureReason: String? = null,
) {
    init {
        require(instanceId.isNotBlank() && password.length >= 32)
        require(pid > 0 && port in 1..65535)
    }

    override fun toString(): String = "BackendEndpoint(instance=$instanceId, pid=$pid, port=$port)"
}
