package io.github.stream29.kodex.backend

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Independent of business/grant protocols. Strict JSON at this fixed exact-Origin POST route. */
public const val LocalFrontendHostProtocol: Int = 1
public const val LocalFrontendBootstrapProtocol: Int = 2
public const val LocalFrontendPairingProtocol: Int = 1
public const val LocalFrontendHostPath: String = "/frontend-host"
public const val LocalFrontendPairingPath: String = "/frontend-pair"
public const val LocalFrontendHostMaxFrameBytes: Int = 65_536
public const val LocalFrontendHostOwnerLeaseMillis: Long = 60_000
public const val LocalFrontendHostRenewIntervalMillis: Long = 20_000
public const val LocalFrontendHostMaxOwners: Int = 32
public const val LocalFrontendHostMaxCallbacksPerOwner: Int = 8
public const val LocalFrontendHostCallbackWaitMillis: Long = 600_000
public const val LocalFrontendHostMaxUrlBytes: Int = 16_384
public const val LocalFrontendHostMaxStateBytes: Int = 1_024
public const val LocalFrontendHostMaxErrorBytes: Int = 8_192
public const val LocalFrontendHostErrorQueueCapacity: Int = 64
public const val LocalFrontendPairingTicketLifetimeMillis: Long = 60_000

/** Readiness of the actual adapter, not a promise inferred from the existence of a Native window. */
@Serializable
public enum class FrontendCapabilityStatus {
    Ready, Negotiating, NoCompanion, IncompatibleProtocol, PermissionDenied,
    OwnerClosed, BackendOffline, ServiceFailed,
}

/** Four independent projections; no adapter may advertise Ready before acquiring its resources. */
@Serializable
public data class FrontendHostCapabilities(
    public val directoryBrowsing: FrontendCapabilityStatus,
    public val contextPathEvaluation: FrontendCapabilityStatus,
    public val oauthCallbacks: FrontendCapabilityStatus,
    public val frontendErrorObservation: FrontendCapabilityStatus,
)

/** Server-generated, non-reusable opaque identity. Not a client-chosen name or durable login id. */
@Serializable
public class LocalFrontendOwner(public val value: String)

/** Opaque reservation scoped to one exact owner and host instance; never shared between pages. */
@Serializable
public class LocalOAuthReservation(public val value: String)

/**
 * Scoped Native companion capability, issued for one exact Origin/Host/backend/launch.
 * [baseUrl] is the canonical loopback HTTP origin of the companion, never a forwarding target.
 * [credential] is independently random/short-lived, never a backend management password.
 * [launchDirectory] and [executionEnvironmentId] describe the frontend device's fixed execution
 * environment; they are informational server output, not request-controlled execution arguments.
 * Directory/Context operations still go directly to the backend business RPC.
 */
@Serializable
public class LocalFrontendHostDescriptor(
    public val protocol: Int,
    public val hostInstanceId: String,
    public val backendInstanceId: String,
    public val origin: String,
    public val baseUrl: String,
    public val credential: String,
    public val owner: LocalFrontendOwner,
    public val executionEnvironmentId: String,
    public val launchDirectory: String,
    public val leaseMillis: Long,
    public val capabilities: FrontendHostCapabilities,
)

/**
 * Exact authorization registered before browser launch; ACK must echo all three fields unchanged.
 * The companion validates one unique state in this URL and its reserved redirect, and binds at most
 * once to this backend's positive attempt id. Neither state nor URL is logged, persisted or replayed.
 */
@Serializable
public class LocalOAuthBinding(
    public val attemptId: Long,
    public val state: String,
    public val authorizationUrl: String,
) {
    init {
        require(attemptId > 0 && state.isNotBlank() && authorizationUrl.isNotBlank()) {
            "An OAuth binding requires a positive attempt and nonempty authorization data."
        }
    }
}

@Serializable
public enum class LocalOAuthProvider { OpenAi, Mcp }

/**
 * Credential-bearing body only; never credentials in path/query/cookie.
 * The server requires exact Host/Origin, protocol, host instance, credential and opaque owner,
 * rejecting expired/cross-owner resources before doing work. Unknown JSON fields are rejected.
 * Automatic entry is same-origin; manual pairing allows only its explicitly approved exact Origin,
 * with exact-origin CORS when necessary, never wildcard/cookie authorization.
 * Every request and response is bounded by MaxFrameBytes (UTF-8); oversize is rejected, not truncated.
 */
@Serializable
public class LocalFrontendHostCall(
    public val protocol: Int,
    public val hostInstanceId: String,
    public val credential: String,
    public val owner: LocalFrontendOwner,
    public val request: LocalFrontendHostRequest,
)

/** Closed operation set: no command, cwd/env execution, arbitrary URL open or HTTP forwarding. */
@Serializable
public sealed interface LocalFrontendHostRequest {
    /**
     * Bind the actual callback socket before replying. OpenAI uses the existing fixed loopback
     * candidates and a null mcpRedirectUri; MCP requires its configured loopback redirect, never an
     * arbitrary listen address.
     * Reserves only local resources; it neither starts backend login nor holds PKCE/credentials.
     */
    @Serializable
    @SerialName("reserve_callback")
    public class ReserveCallback(
        public val provider: LocalOAuthProvider,
        public val mcpRedirectUri: String?,
    ) : LocalFrontendHostRequest

    /** Arm exactly this reservation and await its bind ACK before exposing/launching the URL. */
    @Serializable
    @SerialName("bind_callback")
    public class BindCallback(
        public val reservation: LocalOAuthReservation,
        public val binding: LocalOAuthBinding,
    ) : LocalFrontendHostRequest

    /** Await one matching callback; unrelated/duplicate states never complete or consume it. */
    @Serializable
    @SerialName("await_callback")
    public class AwaitCallback(public val reservation: LocalOAuthReservation) : LocalFrontendHostRequest

    /**
     * Open only the URL already registered by BindCallback for this exact reservation/attempt.
     * A lost response is outcome-unknown: never automatically reopen or rebind.
     * UserRejected means the local launcher did not accept the handoff; a later explicit
     * Retry browser action may try that same registered URL, never an automatic retry.
     */
    @Serializable
    @SerialName("open_registered_authorization")
    public class OpenRegisteredAuthorization(
        public val reservation: LocalOAuthReservation,
        public val attemptId: Long,
    ) : LocalFrontendHostRequest

    /** Idempotent local release, never backend explicit cancel or accepted exchange cancellation. */
    @Serializable
    @SerialName("close_callback")
    public class CloseCallback(public val reservation: LocalOAuthReservation) : LocalFrontendHostRequest

    /**
     * Finite live notification input, not a shell request or a durable event.
     * Null retains the existing warning/error semantics. MaxErrorBytes is measured in UTF-8.
     * One owner-wide serial queue uses capacity 64/drop-oldest; no replay or automatic resend.
     * Native runner reads its own existing frontend-Hook projection, never caller-supplied scripts.
     */
    @Serializable
    @SerialName("observe_error")
    public class ObserveError(public val message: String?) : LocalFrontendHostRequest

    /**
     * Renew only this live owner after confirming its exact backend is still available.
     * Never heartbeat/renew the backend, replace its instance or revive an expired owner.
     */
    @Serializable
    @SerialName("renew_owner")
    public data object RenewOwner : LocalFrontendHostRequest

    /**
     * Immediately stop admission, cancel local callbacks/current Hook and discard queued errors.
     * Await resource cleanup before acknowledgement; do not cancel backend accepted work.
     * A page crash uses the finite lease as fallback, not instantaneous Native-exit equivalence.
     */
    @Serializable
    @SerialName("close_owner")
    public data object CloseOwner : LocalFrontendHostRequest
}

@Serializable
public enum class LocalFrontendHostFailure {
    IncompatibleProtocol, Unauthorized, OwnerClosed, BackendOffline, CapabilityUnavailable,
    InvalidRequest, LimitExceeded, ReservationUnavailable, BindingMismatch, AlreadyConsumed,
    CallbackTimedOut, UserRejected, ServiceFailed,
}

/** Admission is not execution success. Dropped means this observation was not queued. */
@Serializable
public enum class LocalErrorObservationAdmission { Admitted, Dropped, OwnerClosed }

/** Safe typed outcomes only; no raw provider/process/network exception text. */
@Serializable
public sealed interface LocalFrontendHostReply {
    @Serializable
    @SerialName("callback_reserved")
    public class CallbackReserved(
        public val reservation: LocalOAuthReservation,
        public val redirectUri: String,
    ) : LocalFrontendHostReply

    @Serializable
    @SerialName("callback_bound")
    public class CallbackBound(
        public val reservation: LocalOAuthReservation,
        public val binding: LocalOAuthBinding,
    ) : LocalFrontendHostReply

    /** Transient secret-bearing URL, parsed as data by business RPC, never fetched by this host. */
    @Serializable
    @SerialName("callback_received")
    public class CallbackReceived(
        public val reservation: LocalOAuthReservation,
        public val attemptId: Long,
        public val callbackUrl: String,
    ) : LocalFrontendHostReply

    @Serializable
    @SerialName("authorization_opened")
    public class AuthorizationOpened(
        public val reservation: LocalOAuthReservation,
        public val attemptId: Long,
    ) : LocalFrontendHostReply

    @Serializable
    @SerialName("callback_closed")
    public class CallbackClosed(public val reservation: LocalOAuthReservation) : LocalFrontendHostReply

    @Serializable
    @SerialName("error_observed")
    public class ErrorObserved(public val admission: LocalErrorObservationAdmission) : LocalFrontendHostReply

    /** Same owner/instance, accurate refreshed readiness; never an implicit new credential/owner. */
    @Serializable
    @SerialName("owner_renewed")
    public class OwnerRenewed(
        public val leaseMillis: Long,
        public val capabilities: FrontendHostCapabilities,
    ) : LocalFrontendHostReply

    @Serializable
    @SerialName("owner_closed")
    public data object OwnerClosed : LocalFrontendHostReply

    @Serializable
    @SerialName("failure")
    public class Failure(public val reason: LocalFrontendHostFailure) : LocalFrontendHostReply
}

/** Response correlation must match the exact call; no credential is echoed. */
@Serializable
public class LocalFrontendHostResult(
    public val protocol: Int,
    public val hostInstanceId: String,
    public val owner: LocalFrontendOwner,
    public val reply: LocalFrontendHostReply,
)

/**
 * Explicit manual pairing for static Wasm. [ticket] is a finite, single-use Native-issued pairing
 * capability scoped to the approved exact Origin/instance/launch, not a connection.json password.
 * The Native issuer fixes those bindings; request fields cannot widen its scope.
 * Success returns LocalFrontendBootstrap with BOTH scoped business access and a host descriptor.
 * Refusal/expiry does not fall back to management credentials or silently authorize another Origin.
 */
@Serializable
public class LocalFrontendPairingRequest(
    public val protocol: Int,
    public val ticket: String,
    public val origin: String,
    public val workingDirectory: String,
)

/** Native-issued manual handoff, expiring after at most the pairing ticket lifetime. Never log it. */
@Serializable
public class LocalFrontendPairingOffer(
    public val protocol: Int,
    public val baseUrl: String,
    public val hostInstanceId: String,
    public val backendInstanceId: String,
    public val origin: String,
    public val workingDirectory: String,
    public val ticket: String,
    public val expiresInMillis: Long,
)
