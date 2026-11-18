package io.github.stream29.kodex.app.webbackendfixture

import io.github.stream29.kodex.openai.FunctionCallOutputBody
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesApiRequest
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Product-path browser and PTY fixture. No external SDK call or arbitrary command input. */
internal class TerminalInspectorFixture(private val directory: Path, private val intentScenario: String?) {
    private val issued = MutableStateFlow(false)
    private val waitIssued = MutableStateFlow(false)
    private val followupsIssued = MutableStateFlow(false)

    fun response(request: ResponsesApiRequest): Flow<ResponsesStreamEvent>? {
        if (issued.compareAndSet(expect = false, update = true)) return initialResponse()
        if (intentScenario == null) return null
        val output = request.input.filterIsInstance<ResponseItem.FunctionCallOutput>()
        val command = output.lastOrNull { it.callId == ExecCall } ?: return null
        if (intentScenario == "watch" && waitIssued.compareAndSet(expect = false, update = true)) {
            val body = (command.output.body as FunctionCallOutputBody.Text).text
            val terminalId = Json.parseToJsonElement(body).jsonObject.getValue("session_id").jsonPrimitive.int
            return flowOf(
                ResponsesStreamEvent.OutputItemDone(0, ResponseItem.FunctionCall(
                    name = "await_event", callId = AwaitCall,
                    arguments = """{"events":[{"type":"terminal_exited","session_id":$terminalId},{"type":"timeout","duration_ms":35000}]}""",
                )),
                ResponsesStreamEvent.Completed(Response(id = "terminal-intent-await", endTurn = false)),
            )
        }
        if (intentScenario == "watch" && output.none { it.callId == AwaitCall }) return null
        if (!followupsIssued.compareAndSet(expect = false, update = true)) return null
        val forbidden = Path(directory, "invalid-command-started").toString()
        return flowOf(
            exec(0, "intent-quick-call", "printf '%s\\n' FIXTURE_QUICK", "Quick intent fixture", 10_000),
            exec(1, "intent-invalid-call", "touch ${quote(forbidden)}", null, 1_000),
            ResponsesStreamEvent.Completed(Response(id = "terminal-intent-followups", endTurn = false)),
        )
    }

    private fun initialResponse(): Flow<ResponsesStreamEvent> {
        val launch = Path(directory, "emit-terminal-inspector").toString()
        // The owning offline driver creates this fixed executable in a brand-new temporary directory.
        return flowOf(
            exec(0, ExecCall, "exec /bin/sh ${quote(launch)}", "Inspect fixture output", if (intentScenario == null) 10_000 else 650),
            ResponsesStreamEvent.Completed(Response(id = "terminal-inspector-fixture-exec", endTurn = false)),
        )
    }

    private fun exec(index: Long, callId: String, command: String, intent: String?, yieldMillis: Int): ResponsesStreamEvent.OutputItemDone {
        val arguments = buildJsonObject {
            put("cmd", command)
            if (intent != null) put("name", intent)
            put("yield_time_ms", yieldMillis)
            put("max_output_tokens", 100)
        }.toString()
        return ResponsesStreamEvent.OutputItemDone(index, ResponseItem.FunctionCall(name = "exec_command", callId = callId, arguments = arguments))
    }

    private fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private companion object {
        const val ExecCall = "terminal-inspector-fixture-call"
        const val AwaitCall = "terminal-intent-await-call"
    }
}
