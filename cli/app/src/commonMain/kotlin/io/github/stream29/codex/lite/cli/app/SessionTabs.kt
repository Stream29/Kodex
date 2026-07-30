package io.github.stream29.codex.lite.cli.app

import io.github.stream29.codex.lite.cli.session.RootSessionEntry

/** A target represented by one visible top-level CLI tab. */
internal sealed interface SessionTabTarget {
    /** A not-yet-materialized root session with an independent editor draft. */
    data class NewSession(
        val id: Long,
        val ordinal: Int,
    ) : SessionTabTarget

    /** One opened, persisted root session. */
    data class OpenSession(
        val sessionIndex: Int,
    ) : SessionTabTarget
}

/** Immutable ordering and selection rules for the application's visible tabs. */
internal data class SessionTabRegistryState(
    val tabs: List<SessionTabTarget>,
    val activeTarget: SessionTabTarget,
) {
    init {
        require(tabs.isNotEmpty()) { "The tab registry must contain an active target." }
        require(activeTarget in tabs) { "The active target must be present in the tab registry." }
    }

    fun select(target: SessionTabTarget): SessionTabRegistryState {
        require(target in tabs) { "Cannot select a tab that is not open." }
        return copy(activeTarget = target)
    }

    fun addNew(target: SessionTabTarget.NewSession): SessionTabRegistryState {
        require(target !in tabs) { "New session tab ${target.id} is already open." }
        return copy(tabs = tabs + target, activeTarget = target)
    }

    fun openSession(sessionIndex: Int): SessionTabRegistryState {
        val target = SessionTabTarget.OpenSession(sessionIndex)
        return if (target in tabs) {
            copy(activeTarget = target)
        } else {
            copy(tabs = tabs + target, activeTarget = target)
        }
    }

    /** Replaces a virtual tab at its current position after it creates its persisted root. */
    fun materialize(newSession: SessionTabTarget.NewSession, sessionIndex: Int): SessionTabRegistryState {
        val newTabIndex = tabs.indexOf(newSession)
        require(newTabIndex >= 0) { "Cannot materialize a New session tab that is not open." }
        val persisted = SessionTabTarget.OpenSession(sessionIndex)
        if (persisted in tabs) {
            return copy(
                tabs = tabs.filterNot { target -> target == newSession },
                activeTarget = if (activeTarget == newSession) persisted else activeTarget,
            )
        }
        return copy(
            tabs = tabs.mapIndexed { index, target -> if (index == newTabIndex) persisted else target },
            activeTarget = if (activeTarget == newSession) persisted else activeTarget,
        )
    }

    /** Removes one visible tab and returns `null` when a new fallback tab is needed. */
    fun close(target: SessionTabTarget): SessionTabRegistryState? {
        val closedIndex = tabs.indexOf(target)
        require(closedIndex >= 0) { "Cannot close a tab that is not open." }
        val remaining = tabs.toMutableList().apply { removeAt(closedIndex) }
        if (remaining.isEmpty()) return null
        val nextActive = if (activeTarget == target) {
            remaining.getOrNull(closedIndex) ?: remaining.last()
        } else {
            activeTarget
        }
        return SessionTabRegistryState(tabs = remaining, activeTarget = nextActive)
    }

    /** Drops tabs whose persisted roots disappeared from the repository catalog. */
    fun retainOpenSessions(sessionIndexes: Set<Int>): SessionTabRegistryState? {
        val activeIndex = tabs.indexOf(activeTarget)
        val remaining = tabs.filter { target ->
            target !is SessionTabTarget.OpenSession || target.sessionIndex in sessionIndexes
        }
        if (remaining.isEmpty()) return null
        val nextActive = activeTarget.takeIf { it in remaining }
            ?: remaining.getOrNull(activeIndex.coerceAtMost(remaining.lastIndex))
            ?: remaining.last()
        return SessionTabRegistryState(tabs = remaining, activeTarget = nextActive)
    }
}

/** A tab prepared for rendering; persisted tabs carry their opened root ViewModel entry. */
internal data class SessionTabViewState(
    val target: SessionTabTarget,
    val selected: Boolean,
    val rootSession: RootSessionEntry? = null,
    val newSessionName: String? = null,
)
