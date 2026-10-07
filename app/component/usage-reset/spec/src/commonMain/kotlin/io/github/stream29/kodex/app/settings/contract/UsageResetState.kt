package io.github.stream29.kodex.app.settings.contract

import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetOutcome
import kotlin.time.Instant

/**
 * One frontend choice, not a wire DTO, account binding or private reset attempt.
 *
 * Render title, description and expiry in the picker and confirmation. A null
 * expiry MUST read "Expiry unknown", never imply unlimited validity.
 *
 * @property creditId Preserved nullable legacy field; only non-blank, specific IDs
 * can be selected/consumed by the component. Null is never an auto-selection request.
 * @property title Non-blank display title; missing backend title uses "Full reset".
 * @property description Non-blank explanation; missing backend description uses
 * "Reset current usage limits.".
 * @property expiresAt Backend expiry, null when unknown.
 * @throws IllegalArgumentException if a non-null ID, title or description is blank.
 */
public data class UsageResetOption(
    public val creditId: String?,
    public val title: String,
    public val description: String,
    public val expiresAt: Instant?,
) {
    init {
        require(creditId == null || creditId.isNotBlank()) {
            "A usage-reset credit id must be null or non-blank."
        }
        require(title.isNotBlank()) { "A usage-reset title must not be blank." }
        require(description.isNotBlank()) { "A usage-reset description must not be blank." }
    }
}

/**
 * Choices copied in backend detail order from one usage snapshot; not a wire DTO.
 * A count alone is not permission to reset. No sorting, expiry inference or
 * automatic selection is performed.
 *
 * @property availableCount Known count, or detail count when unknown, raised to at
 * least the number of options. Does not assert that these credits remain usable.
 * @property options Nonempty detail choices; renderer preserves order.
 * @throws IllegalArgumentException if count is nonpositive, options are empty or
 * their number exceeds count.
 */
public data class UsageResetRequest(
    public val availableCount: Long,
    public val options: List<UsageResetOption>,
) {
    init {
        require(availableCount > 0) { "A usage-reset request must have an available reset." }
        require(options.isNotEmpty()) { "A usage-reset request must have a selectable option." }
        require(availableCount >= options.size) {
            "A usage-reset request cannot expose more options than available resets."
        }
    }
}

/**
 * Entire non-wire dialog workflow. Renderer owns only focus/layout, never another
 * selection or consumption authority. The unused legacy Preparing branch is
 * intentionally absent: there is no preparation operation or RPC.
 */
public sealed interface UsageResetState {
    /** Render nothing; does not dispose the reusable child. */
    public data object Hidden : UsageResetState

    /**
     * Render count and all options with description and explicit expiry; Cancel
     * starts focused. Choosing never consumes. Each choice passes its specific ID.
     * @property request Snapshot of details used to populate this picker.
     */
    public data class Choosing(public val request: UsageResetRequest) : UsageResetState

    /**
     * Render title, description and explicit expiry. Go back starts focused;
     * Use reset passes THIS exact object to confirm, not an equal copy or a later
     * read of state. Escape goes back to fresh details rather than consuming.
     * @property option The selected detail row, retained unchanged for confirmation.
     */
    public data class Confirming(public val option: UsageResetOption) : UsageResetState

    /**
     * Render "Resetting your usage…" without actions; dismissal is ineffective
     * during the live operation. Cancellation is not proof of backend rollback.
     * @property option The exact row submitted to the dependency.
     */
    public data class Consuming(public val option: UsageResetOption) : UsageResetState

    /**
     * Render an unknown-result warning: the reset may have been used. Close starts
     * focused; Try again returns to fresh selection, never resends consumption.
     * The single refresh may still be active, during which commands are ignored.
     * @property option The submitted row, not an attempt/replay token.
     */
    public data class ConsumeFailed(public val option: UsageResetOption) : UsageResetState

    /**
     * No usable detail rows (including count-only, known empty and no snapshot).
     * Explain that a specific reset must be selected; Close starts focused and
     * Refresh explicitly reloads usage and then rebuilds choices. No autochoice.
     */
    public data object PreparationFailed : UsageResetState

    /**
     * Render all four outcomes distinctly: Reset => "Usage reset.";
     * NothingToReset => no reset needed; AlreadyRedeemed => already used
     * successfully; NoCredit => selected credit no longer available (or no resets
     * available for the preserved legacy false flag). Close starts focused.
     * Refresh failure must not replace this known result with an unknown failure.
     *
     * @property outcome Definitive backend result, unchanged.
     * @property selectedCredit Legacy presentation flag; this component always
     * publishes true because consumption requires an explicit credit.
     */
    public data class Completed(
        public val outcome: CodexRateLimitResetOutcome,
        public val selectedCredit: Boolean,
    ) : UsageResetState
}
