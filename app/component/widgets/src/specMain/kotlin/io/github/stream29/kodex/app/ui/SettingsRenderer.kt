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
    public fun Section(title: String, content: @Composable () -> Unit): Unit

    /** Section heading paired with inline header actions (e.g., list management controls). */
    @Composable
    public fun SectionHeader(title: String, actions: @Composable () -> Unit): Unit

    @Composable
    public fun Item(label: String, supportingText: String?, enabled: Boolean, content: @Composable () -> Unit): Unit

    /** Full-width selectable or navigable row button within a Settings list/import surface. */
    @Composable
    public fun Entry(label: String, enabled: Boolean, onClick: () -> Unit): Unit

    @Composable
    public fun Action(
        label: String,
        onClick: () -> Unit,
        enabled: Boolean,
        autoFocus: Boolean,
        role: UiActionRole,
    ): Unit

    /** Controlled value from the owning child; activation emits a Boolean without optimistic storage. */
    @Composable
    public fun Toggle(
        label: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
        enabled: Boolean,
        supportingText: String?,
    ): Unit

    /** Compact inline checkbox for multi-control [Item] rows with explicit accessibility description. */
    @Composable
    public fun InlineToggle(
        label: String,
        contentDescription: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
        enabled: Boolean,
    ): Unit

    /**
     * Single-line form input keyed by [token] so replacing a dialog/preview resets renderer-owned
     * cursor/focus state while same-frame edits across sibling fields preserve each other's drafts.
     */
    @Composable
    public fun TextField(
        token: Any,
        label: String,
        value: String,
        autoFocus: Boolean,
        onChange: (String) -> Unit,
    ): Unit

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
    ): Unit
}
