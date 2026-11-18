package io.github.stream29.kodex.app.webbackendfixture

import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.io.files.Path
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Product-path browser and PTY fixture. No external SDK call or arbitrary command input. */
internal class TerminalInspectorFixture(private val directory: Path) {
    private val issued = MutableStateFlow(false)

    fun firstResponse(): Flow<ResponsesStreamEvent>? {
        if (!issued.compareAndSet(expect = false, update = true)) return null
        val launch = Path(directory, "emit-terminal-inspector").toString()
        // The owning offline driver creates this fixed executable in a brand-new temporary directory.
        val command = "exec /bin/sh '${launch.replace("'", "'\\''")}'"
        val arguments = buildJsonObject {
            put("cmd", command)
            put("yield_time_ms", 10_000)
            put("max_output_tokens", 100)
        }.toString()
        return flowOf(
            ResponsesStreamEvent.OutputItemDone(0, ResponseItem.FunctionCall(
                name = "exec_command", callId = "terminal-inspector-fixture-call", arguments = arguments,
            )),
            ResponsesStreamEvent.Completed(Response(id = "terminal-inspector-fixture-exec", endTurn = false)),
        )
    }
}
