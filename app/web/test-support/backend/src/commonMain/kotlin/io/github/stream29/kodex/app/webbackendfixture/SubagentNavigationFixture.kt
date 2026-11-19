package io.github.stream29.kodex.app.webbackendfixture

import io.github.stream29.kodex.openai.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.io.files.Path
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class SubagentNavigationFixture(private val temporaryDirectory: Path) {
    fun response(request: ResponsesApiRequest): Flow<ResponsesStreamEvent> {
        val input = request.input.filterIsInstance<ResponseItem.Message>()
            .flatMap { it.content }.filterIsInstance<ContentItem.InputText>().map { it.text }
        val outputs = request.input.filterIsInstance<ResponseItem.FunctionCallOutput>().map { it.callId }.toSet()
        return when {
            "NAV_FIXTURE_ROOT" in input && "nav_spawn_a" !in outputs -> spawn("a", "NAV_FIXTURE_A")
            "NAV_FIXTURE_A" in input && "nav_spawn_c" !in outputs -> spawn("c", "NAV_FIXTURE_C")
            "NAV_ONLY_C" in input -> flow { awaitCancellation() }
            else -> {
                val text = when {
                    "NAV_FIXTURE_C" in input -> "NAV_C_READY"
                    "NAV_FIXTURE_A" in input -> "NAV_A_READY"
                    else -> "NAV_ROOT_READY"
                }
                flowOf(
                    ResponsesStreamEvent.OutputItemDone(0, ResponseItem.Message(
                        id = ResponseItemId(text), role = MessageRole.Assistant, phase = MessagePhase.FinalAnswer,
                        content = listOf(ContentItem.OutputText(text)),
                    )),
                    ResponsesStreamEvent.Completed(Response(id = text, endTurn = true)),
                )
            }
        }
    }

    private fun spawn(suffix: String, task: String): Flow<ResponsesStreamEvent> {
        val directory = Path(temporaryDirectory, "nav")
        val call = "nav_spawn_$suffix"
        val args = buildJsonObject {
            put("cmd", """
                set -eu
                '${Path(directory, "kodex")}' agent spawn --name 'Worker-$suffix' --message $task > '${Path(directory, "$suffix.tmp")}'
                mv '${Path(directory, "$suffix.tmp")}' '${Path(directory, "$suffix.json")}'
            """.trimIndent())
            put("name", "Delegate navigation fixture")
            put("yield_time_ms", 1000)
        }.toString()
        return flowOf(
            ResponsesStreamEvent.OutputItemDone(0, ResponseItem.FunctionCall(name = "exec_command", arguments = args, callId = call)),
            ResponsesStreamEvent.Completed(Response(id = call, endTurn = false)),
        )
    }
}
