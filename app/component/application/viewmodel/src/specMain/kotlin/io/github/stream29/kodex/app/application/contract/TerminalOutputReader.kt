@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.app.application.contract

import io.github.stream29.kodex.app.agent.contract.AgentShellSession
import io.github.stream29.kodex.app.agent.contract.AgentShellSessionRegistry
import kotlinx.coroutines.flow.StateFlow

public sealed interface TerminalOutputItem {
    public val id: Long

    /** A line or bounded fragment of a long line. The text never contains terminal controls. */
    public data class Text(
        override val id: Long,
        public val text: String,
        public val endsLine: Boolean,
    ) : TerminalOutputItem

    public data class BackendOmission(
        override val id: Long,
        public val bytes: ULong,
    ) : TerminalOutputItem

    public data class WindowEviction(public val items: Long) : TerminalOutputItem {
        override val id: Long get() = 0L
    }
}

public sealed interface TerminalOutputPhase {
    public data object Reading : TerminalOutputPhase
    public data class Complete(public val exitCode: Int) : TerminalOutputPhase
    public data object Removed : TerminalOutputPhase
    public data object SessionInactive : TerminalOutputPhase
    public data object ReadFailed : TerminalOutputPhase
}

public class TerminalOutputState(
    public val items: List<TerminalOutputItem>,
    public val phase: TerminalOutputPhase,
)

/** One bounded snapshot stream for a single explicitly opened inspector. */
public expect class TerminalOutputReader(session: AgentShellSession, registry: AgentShellSessionRegistry) {
    public val state: StateFlow<TerminalOutputState>

    /** Reads until drained or no longer available. Call once in the visible inspector's scope. */
    public suspend fun observe(): Unit
}
