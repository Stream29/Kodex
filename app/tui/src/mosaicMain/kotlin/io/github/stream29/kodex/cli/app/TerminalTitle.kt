package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState

internal data class OpenAgentSummary(
    val agentCount: Int,
    val runningAgentCount: Int,
)

internal fun summarizeOpenAgents(tabs: List<SessionTabRenderState>): OpenAgentSummary {
    return OpenAgentSummary(
        agentCount = tabs.size,
        runningAgentCount = tabs.count(SessionTabRenderState::running),
    )
}

internal fun terminalTitleText(agentCount: Int, runningCount: Int): String {
    require(agentCount >= 0) { "Agent count cannot be negative." }
    require(runningCount in 0..agentCount) {
        "Running count must belong to the counted Agents."
    }
    return "$agentCount ${if (agentCount == 1) "agent" else "agents"} ($runningCount running)"
}

internal fun terminalTitleControlSequence(title: String): String =
    "\u001B]0;$title\u0007"

internal fun writeTerminalTitle(
    title: String,
    write: (String) -> Unit = { sequence -> print(sequence) },
) {
    write(terminalTitleControlSequence(title))
}

/** Restores the terminal title when the CLI host exits. */
public actual fun resetTerminalTitle(): Unit {
    writeTerminalTitle("")
}

@Composable
internal fun TerminalTitleEffect(
    agentCount: Int,
    runningCount: Int,
    write: (String) -> Unit = { sequence -> print(sequence) },
) {
    val title = terminalTitleText(agentCount, runningCount)
    val currentWrite = rememberUpdatedState(write)
    DisposableEffect(title) {
        writeTerminalTitle(title, currentWrite.value)
        onDispose {}
    }
}
