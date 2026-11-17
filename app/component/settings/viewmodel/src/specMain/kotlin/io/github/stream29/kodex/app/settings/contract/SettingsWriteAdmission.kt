package io.github.stream29.kodex.app.settings.contract

/**
 * Result of synchronous admission to the shared application write queue, never a persistence receipt.
 * [Accepted] freezes the captured baseline and payload before returning so queued writes survive
 * editor release, page hiding, component close, and owner cancellation. [Rejected] admits nothing
 * and carries a local, credential-free explanation for dialog display.
 */
public sealed interface SettingsWriteAdmission {
    public data object Accepted : SettingsWriteAdmission
    public data class Rejected(public val message: String) : SettingsWriteAdmission
}
