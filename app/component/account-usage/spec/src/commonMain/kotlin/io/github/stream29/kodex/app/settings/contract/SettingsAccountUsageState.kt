package io.github.stream29.kodex.app.settings.contract

import io.github.stream29.kodex.openai.accountusage.CodexAccountUsageSnapshot
import kotlinx.serialization.Serializable

/**
 * Account-usage projection that keeps reset-attempt idempotency data private. Same original
 * package/serializer descriptors as the GlobalRpc wire DTO; no UI or RPC dependency is needed.
 * Previous snapshots are source-owned same-account fallbacks, never synthesized by the child.
 */
@Serializable
public sealed interface SettingsAccountUsageState {
    /** Render sign-in guidance and hide actions; unknown usage is not zero usage. */
    @Serializable
    public data object Unavailable : SettingsAccountUsageState

    /**
     * Render initial loading without a snapshot, otherwise the fallback plus refreshing status.
     * Disable UI refresh/reset, without implying global command deduplication.
     * @property previous Null when no same-account fallback snapshot is available.
     */
    @Serializable
    public data class Loading(
        public val previous: CodexAccountUsageSnapshot? = null,
    ) : SettingsAccountUsageState

    /** Render full snapshot and actions; reset intent opens selection, never consumes a credit. */
    @Serializable
    public data class Available(
        public val snapshot: CodexAccountUsageSnapshot,
    ) : SettingsAccountUsageState

    /**
     * Render source-sanitized failure message and optional same-account fallback; permit refresh.
     * @property previous Null when no same-account fallback snapshot is available.
     * @throws IllegalArgumentException if message is blank.
     */
    @Serializable
    public data class Failed(
        public val message: String,
        public val previous: CodexAccountUsageSnapshot? = null,
    ) : SettingsAccountUsageState {
        init {
            require(message.isNotBlank()) {
                "A Settings account-usage failure message must not be blank."
            }
        }
    }

    /** Render retained snapshot plus redemption progress; disable renderer actions. */
    @Serializable
    public data class Redeeming(
        public val snapshot: CodexAccountUsageSnapshot,
    ) : SettingsAccountUsageState
}

/** Returns only the source-retained same-account snapshot, or null; never caches a previous account. */
public fun SettingsAccountUsageState.snapshotOrNull(): CodexAccountUsageSnapshot? =
    when (this) {
        is SettingsAccountUsageState.Available -> snapshot
        is SettingsAccountUsageState.Failed -> previous
        is SettingsAccountUsageState.Loading -> previous
        is SettingsAccountUsageState.Redeeming -> snapshot
        SettingsAccountUsageState.Unavailable -> null
    }
