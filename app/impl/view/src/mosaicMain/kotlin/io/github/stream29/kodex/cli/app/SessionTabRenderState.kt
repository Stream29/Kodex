package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import io.github.stream29.kodex.app.session.contract.NewSessionViewModel
import io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
import io.github.stream29.kodex.app.session.contract.SessionViewModel

/**
 * Application-owned projection consumed by the Session Tab Bar component.
 *
 * The tab component receives an immutable presentation snapshot; this
 * projection is the only place where session ViewModels are collected and
 * translated into that snapshot's source data. It intentionally contains the
 * exact session object for host callback admission, while the component itself
 * only exposes [SessionViewModel] identities to callbacks.
 */
internal data class SessionTabRenderState(
    val target: SessionViewModel,
    val selected: Boolean,
    val sessionName: String,
    val running: Boolean = false,
)

/**
 * Collects the current application-owned session tab snapshot.
 *
 * The list order and selected flag follow [tabs] and [selectedIndex] exactly.
 * No tab action is performed here; the Session Tab Bar component owns only
 * renderer-local focus/scroll/geometry and calls the host with exact
 * component identities.
 */
@Composable
internal fun collectSessionTabRenderStates(
    tabs: List<SessionViewModel>,
    selectedIndex: Int,
): List<SessionTabRenderState> = buildList(tabs.size) {
    tabs.forEachIndexed { index, target ->
        key(SessionRenderKey(target)) {
            val name by target.name.collectAsState()
            val running = when (target) {
                is NewSessionViewModel -> false
                is PersistedSessionViewModel -> {
                    val agent by target.rootAgent.collectAsState()
                    agent?.running?.collectAsState()?.value ?: false
                }
                else -> error("Unsupported Session ViewModel: ${target::class}")
            }
            add(
                SessionTabRenderState(
                    target = target,
                    selected = index == selectedIndex,
                    sessionName = name,
                    running = running,
                ),
            )
        }
    }
}

/** Compose keys must follow the same referential ownership as Application commands. */
internal class SessionRenderKey(private val target: SessionViewModel) {
    override fun equals(other: Any?): Boolean = other is SessionRenderKey && other.target === target
    override fun hashCode(): Int = 0
}
