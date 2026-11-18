package io.github.stream29.kodex.backend

import kotlinx.serialization.Serializable

/** Optional Native-managed browser admission; independent of business compatibility. */
public const val LocalBrowserGrantProtocol: Int = 1
public const val LocalFrontendBootstrapProtocol: Int = 3
public const val LocalFrontendBootstrapMaxBytes: Int = 65_536

/** Native management only. A random credential authorizes browser business access, never control. */
@Serializable
public class LocalBrowserGrant(
    public val protocol: Int,
    public val origin: String,
    public val password: String,
    public val workingDirectory: String,
)

/** Bootstrap protocol 3: one host-scoped business endpoint and the backend-validated directory. */
@Serializable
public class LocalFrontendBootstrap(
    public val protocol: Int,
    public val endpoint: LocalBrowserEndpoint,
    public val workingDirectory: String,
)

/** Capability sent in a same-origin JSON body, never in an HTTP path or query. */
@Serializable
public class LocalWebTicket(public val ticket: String)
