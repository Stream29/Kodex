package io.github.stream29.kodex.cli.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState

internal data class OpenSessionSummary(
    val sessionCount: Int,
    val runningSessionCount: Int,
)

internal fun summarizeOpenSessions(tabs: List<SessionTabRenderState>): OpenSessionSummary {
    val sessions = tabs.filter { tab ->
        tab.target is io.github.stream29.kodex.app.session.contract.PersistedSessionViewModel
    }
    return OpenSessionSummary(
        sessionCount = sessions.size,
        runningSessionCount = sessions.count(SessionTabRenderState::running),
    )
}

internal fun terminalTitleText(sessionCount: Int, runningCount: Int): String {
    require(sessionCount >= 0) { "Session count cannot be negative." }
    require(runningCount in 0..sessionCount) {
        "Running count must belong to the counted sessions."
    }
    return "$sessionCount sessions ($runningCount running)"
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
public fun resetTerminalTitle() {
    writeTerminalTitle("")
}

@Composable
internal fun TerminalTitleEffect(
    sessionCount: Int,
    runningCount: Int,
    write: (String) -> Unit = { sequence -> print(sequence) },
) {
    val title = terminalTitleText(sessionCount, runningCount)
    val currentWrite = rememberUpdatedState(write)
    DisposableEffect(title) {
        writeTerminalTitle(title, currentWrite.value)
        onDispose {}
    }
}
