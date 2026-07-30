package io.github.stream29.codex.lite.cli.auth

import io.github.stream29.codex.lite.openai.OpenAiSubscriptionPlan
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
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
 */
internal data class SubscriptionJwtClaims(
    val expiresAt: Instant?,
    val accountId: String?,
    val planType: OpenAiSubscriptionPlan?,
)

/**
 * JWT payload fields consumed by Codex Lite.
 *
 * @property expiresAtEpochSeconds Nullable because an opaque or incomplete
 * access token may omit `exp`; `null` selects the refresh-time fallback.
 * @property auth Nullable because tokens used outside a ChatGPT workspace may
 * omit the OpenAI auth namespace; `null` means no workspace claims exist.
 */
@Serializable
private data class SubscriptionJwtPayload(
    @SerialName("exp")
    val expiresAtEpochSeconds: Long? = null,
    @SerialName("https://api.openai.com/auth")
    val auth: SubscriptionJwtAuth? = null,
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
    return SubscriptionJwtClaims(expiresAt, accountId, planType)
}

/** @return Decoded bytes, or `null` when [segment] is not valid Base64 URL data. */
private fun decodeJwtSegmentOrNull(segment: String): ByteArray? {
    val padding = (4 - segment.length % 4) % 4
    return runCatching {
        Base64.UrlSafe.decode(segment + "=".repeat(padding))
    }.getOrNull()
}
