package io.github.stream29.kodex.backend

import kotlinx.serialization.Serializable

/** Optional Native-managed browser admission; independent of business compatibility. */
public const val LocalBrowserGrantProtocol: Int = 1

/** Native management only. A random credential authorizes browser business access, never control. */
@Serializable
public class LocalBrowserGrant(
    public val protocol: Int,
    public val origin: String,
    public val password: String,
    public val workingDirectory: String,
)

/**
 * Bootstrap protocol 2. Both business and companion credentials are scoped; never management access.
 * [host] describes the actual adapter readiness and exact owner, even when a capability is not Ready.
 * Older bootstrap payloads without it must fail negotiation, not default to fabricated capabilities.
 */
@Serializable
public class LocalFrontendBootstrap(
    public val protocol: Int,
    public val endpoint: LocalBrowserEndpoint,
    public val workingDirectory: String,
    public val host: LocalFrontendHostDescriptor,
)

/** Capability sent in a same-origin JSON body, never in an HTTP path or query. */
@Serializable
public class LocalWebTicket(public val ticket: String)
