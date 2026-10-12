package io.github.stream29.kodex.app.sessiontabbar.contract

/**
 * Exact stable identity of one tab admission. Labels are not identities:
 * renaming, ordering and selected-index changes must not redirect callbacks.
 *
 * @throws IllegalArgumentException if [value] is blank.
 */
public data class SessionTabIdentity(public val value: String) {
    init {
        require(value.isNotBlank()) { "A Session tab identity must not be blank." }
    }
}

/**
 * Immutable tab presentation supplied by Application. The component does not
 * retain a registry, query a selected tab, or derive this value from another
 * mutable source. The list order is the render order and is preserved exactly.
 *
 * @throws IllegalArgumentException if [label] is blank.
 */
public data class SessionTabPresentation(
    public val identity: SessionTabIdentity,
    public val label: String,
    public val running: Boolean = false,
) {
    init {
        require(label.isNotBlank()) { "A Session tab label must not be blank." }
    }
}

/**
 * Atomic immutable snapshot consumed by the renderer. [selected] may be null
 * for an empty/transitioning host projection, but when present it must identify
 * one item in [tabs]. Duplicate identities are rejected so exact callbacks
 * cannot be ambiguous.
 *
 * @throws IllegalArgumentException if identities repeat or a non-null selected
 * identity is not present.
 */
public data class SessionTabBarState(
    public val tabs: List<SessionTabPresentation>,
    public val selected: SessionTabIdentity?,
) {
    init {
        require(tabs.map(SessionTabPresentation::identity).distinct().size == tabs.size) {
            "Session tab identities must be unique."
        }
        require(selected == null || tabs.any { it.identity == selected }) {
            "The selected Session tab must be present in the snapshot."
        }
    }
}

/**
 * Exact host callbacks. Application remains the owner of the mutable registry,
 * selected index, session close/archive work and root popup lifecycle. These
 * methods receive only the identity captured from the immutable snapshot; a
 * host adapter must verify that identity is still admitted before acting.
 *
 * Context-menu opening is an intent. Anchor and pointer geometry remain view
 * concerns and are supplied to the host's popup adapter separately.
 */
public interface SessionTabBarCallbacks {
    /** Selects the captured tab identity; stale identities must be ignored. */
    public fun select(identity: SessionTabIdentity): Unit

    /** Requests close of the captured tab identity; stale identities must be ignored. */
    public fun close(identity: SessionTabIdentity): Unit

    /** Opens the root context-menu lifecycle for the captured identity. */
    public fun openContextMenu(identity: SessionTabIdentity): Unit

    /** Creates one new tab through the Application owner. */
    public fun createNewSession(): Unit

    /** Opens the Application-owned session catalog popup. */
    public fun openSessions(): Unit
}
