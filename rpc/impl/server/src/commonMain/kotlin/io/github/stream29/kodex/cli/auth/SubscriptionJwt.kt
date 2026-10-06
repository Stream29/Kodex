package io.github.stream29.kodex.cli.auth

import io.github.stream29.kodex.openai.OpenAiSubscriptionPlan
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.time.Instant

/**
 * Claims used to schedule token refresh and identify the active subscription.
 *
 * @property expiresAt Nullable because opaque or malformed tokens do not expose
 * an expiration; `null` selects the persisted refresh-time fallback.
 * @property accountId Nullable because the ID token may omit the selected
 * workspace; `null` means no account ID was encoded in this token.
 * @property planType Nullable because the ID token may omit the plan or use an
 * unknown value; `null` means no recognized plan is available.
 * @property email Nullable because the token may omit both supported email
 * claims; `null` means no account email was encoded in this token.
 */
internal data class SubscriptionJwtClaims(
    val expiresAt: Instant?,
    val accountId: String?,
    val planType: OpenAiSubscriptionPlan?,
    val email: String?,
)

/**
 * JWT payload fields consumed by Kodex.
 *
 * @property expiresAtEpochSeconds Nullable because an opaque or incomplete
 * access token may omit `exp`; `null` selects the refresh-time fallback.
 * @property email Nullable because newer ID tokens expose email as a standard
 * top-level claim while older variants may use [profile].
 * @property profile Nullable because tokens without the namespaced profile
 * claim may still provide a top-level email.
 * @property auth Nullable because tokens used outside a ChatGPT workspace may
 * omit the OpenAI auth namespace; `null` means no workspace claims exist.
 */
@Serializable
private data class SubscriptionJwtPayload(
    @SerialName("exp")
    val expiresAtEpochSeconds: Long? = null,
    val email: String? = null,
    @SerialName("https://api.openai.com/profile")
    val profile: SubscriptionJwtProfile? = null,
    @SerialName("https://api.openai.com/auth")
    val auth: SubscriptionJwtAuth? = null,
)

/** Email fallback used by ID tokens that omit the standard top-level claim. */
@Serializable
private data class SubscriptionJwtProfile(
    val email: String? = null,
)

/**
 * ChatGPT workspace claims nested in a subscription JWT.
 *
 * @property accountId Nullable because the token may omit a selected
 * workspace; `null` means no account header can be derived from this claim.
 * @property planType Nullable because the token may omit its subscription
 * plan; `null` means no plan can be derived from this claim.
 */
@Serializable
private data class SubscriptionJwtAuth(
    @SerialName("chatgpt_account_id")
    val accountId: String? = null,
    @SerialName("chatgpt_plan_type")
    val planType: String? = null,
)

internal fun String.subscriptionJwtClaims(): SubscriptionJwtClaims {
    val payload = split('.').getOrNull(1)
        ?.let(::decodeJwtSegmentOrNull)
        ?.decodeToString()
        ?.let { json ->
            runCatching {
                OpenAiJsonCodec.decodeFromString<SubscriptionJwtPayload>(json)
            }.getOrNull()
        }
    val expiresAt = payload
        ?.expiresAtEpochSeconds
        ?.let(Instant::fromEpochSeconds)
    val accountId = payload
        ?.auth
        ?.accountId
        ?.takeIf(String::isNotBlank)
    val planType = payload
        ?.auth
        ?.planType
        ?.let(OpenAiSubscriptionPlan::fromRawValue)
    val email = payload
        ?.email
        ?.takeIf(String::isNotBlank)
        ?: payload
            ?.profile
            ?.email
            ?.takeIf(String::isNotBlank)
    return SubscriptionJwtClaims(
        expiresAt = expiresAt,
        accountId = accountId,
        planType = planType,
        email = email,
    )
}

/** @return Decoded bytes, or `null` when [segment] is not valid Base64 URL data. */
private fun decodeJwtSegmentOrNull(segment: String): ByteArray? {
    val padding = (4 - segment.length % 4) % 4
    return runCatching {
        Base64.UrlSafe.decode(segment + "=".repeat(padding))
    }.getOrNull()
}
