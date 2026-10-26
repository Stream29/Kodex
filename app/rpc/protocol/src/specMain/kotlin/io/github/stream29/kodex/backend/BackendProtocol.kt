package io.github.stream29.kodex.backend

import kotlinx.serialization.Serializable

/** Business compatibility is independent of application releases and transport handshakes. */
public const val BackendBusinessProtocol: String = "0.5.2"
public const val BackendControlProtocol: Int = 1

/** Same-machine opt-in browser transport only; not a cloud authentication protocol. */
public const val LocalBrowserProtocol: Int = 1
public const val LocalBrowserHelloMaxBytes: Long = 16_384

/** Untrusted subset of the private Native connection record; not proof of a live backend. */
@Serializable
public class LocalBrowserEndpoint(
    public val instanceId: String,
    public val port: Int,
    public val password: String,
    public val businessProtocol: String,
)

/** Required explicit versions; WebSocket credentials are confined to the initial frame. */
@Serializable
public class LocalBrowserHello(
    public val protocol: Int,
    public val instanceId: String,
    public val password: String,
    public val businessProtocol: String,
    /** Absolute directory on the backend; null selects that local backend's Home. */
    public val workingDirectory: String?,
)

@Serializable
public class LocalBrowserWelcome(
    public val protocol: Int,
    public val instanceId: String,
    public val businessProtocol: String,
    /** Canonical directory validated by the backend, not resolved on the browser's filesystem. */
    public val workingDirectory: String,
)

/** Canonical loopback HTTP origin only; rejects paths, credentials, null and remote origins. */
public expect fun canonicalLocalBrowserOrigin(value: String): String
