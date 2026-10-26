package io.github.stream29.kodex.app.ui

import androidx.compose.runtime.Composable

/** A stable renderer selection key and its display label; never an RPC address or mutable setting. */
public data class UiChoiceOption(public val key: String, public val label: String)

/**
 * Settings field presentation only. The shared page owns field order, options and command wiring.
 * Implementations may keep menu/focus geometry and current rendering callbacks, not business drafts.
 * A renderer instance scopes [Choice]'s field keys; unmounting a field releases its menu.
 */
public interface SettingsRenderer {
    @Composable
    public fun Section(title: String, content: @Composable () -> Unit)

    @Composable
    public fun Item(label: String, supportingText: String?, enabled: Boolean, content: @Composable () -> Unit)

    @Composable
    public fun Action(label: String, onClick: () -> Unit, enabled: Boolean, autoFocus: Boolean)

    /** Controlled value from the owning child; activation emits a Boolean without optimistic storage. */
    @Composable
    public fun Toggle(
        label: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
        enabled: Boolean,
        supportingText: String?,
    )

    /**
     * Option keys are unique in this field. Emit only a captured option's key, and never while disabled.
     * An unknown selected key displays itself without fabricating an option or selecting a fallback.
     * Popup mounting belongs to the renderer's host; this call does not create a second setting owner.
     */
    @Composable
    public fun Choice(
        fieldKey: String,
        label: String,
        options: List<UiChoiceOption>,
        selectedKey: String,
        onSelect: (String) -> Unit,
        enabled: Boolean,
        supportingText: String?,
    )
}
