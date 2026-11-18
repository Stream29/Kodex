package io.github.stream29.kodex.app.webbackendfixture

import io.github.stream29.kodex.app.migration.prepareKodexHome
import io.github.stream29.kodex.app.test.seedTestHistory
import io.github.stream29.kodex.app.test.testSettings
import io.github.stream29.kodex.agentsession.sqlite.SqliteKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.IndexCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.WorkCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.UnstableSnapshot
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecution
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionAction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionResult
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.utils.sqlite.openSqliteConnection
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.*
import kotlinx.io.files.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal suspend fun CoroutineScope.seedHistoryProductFixture(home: Path) {
    prepareKodexHome(home).closeAndJoin()
    seedTestHistory(home, "History sparse fixture", linkedMapOf(
        2 to StableUserMessage(listOf(ContentItem.InputText("HISTORY_SPARSE_OLD"))),
        5 to StableAssistantMessage(listOf(ContentItem.OutputText("HISTORY_SPARSE_LATEST"))),
    ))
    val events = linkedMapOf<Int, IndexCommit>()
    repeat(420) { row ->
        val marker = "HISTORY_ROW_${row.toString().padStart(3, '0')}"
        events[2 + row * 3] = if (row % 2 == 0) {
            StableUserMessage(listOf(ContentItem.InputText(marker)))
        } else {
            StableAssistantMessage(listOf(ContentItem.OutputText(marker)))
        }
    }
    events[1263] = StableAssistantMessage(listOf(ContentItem.OutputText(
        (0 until 100).joinToString("\n") { "HISTORY_LONG_${it.toString().padStart(3, '0')}" },
    )))
    events[1266] = Json.decodeFromString<IndexCommit>(
        """{"type":"plan_update","call_id":"history-plan","item_id":"history-plan","arguments":{"explanation":"History disclosure geometry fixture","plan":[{"step":"HISTORY_DISCLOSURE_FIRST","status":"completed"},{"step":"HISTORY_DISCLOSURE_LAST","status":"in_progress"}]}}""",
    )
    events[1269] = StableAssistantMessage(listOf(ContentItem.OutputText("HISTORY_MANY_LATEST")))
    seedTestHistory(home, "History many fixture", events)
    val repository = SqliteKodexSessionRepository(home, testKodexAgentDependencies())
    try {
        val session = repository.create()
        repository.open(session).runtime.modify { storage ->
            storage.initialize(testSettings("History disclosure fixture", home))
            storage.commit(UnstableSnapshot(emptyList()))
            storage.commit(StableUserMessage(listOf(ContentItem.InputText("HISTORY_TOOL_OLD"))))
            val output = (0 until 100).joinToString("\n") { "HISTORY_TOOL_OUTPUT_$it" }
            storage.commit(
                Json.decodeFromString<WorkCommit>(
                    """{"type":"command_execution_tool_event","call_id":"history-tool","action":{"type":"exec_command","arguments":{"cmd":"history-fixture-not-executed","yield_time_ms":1000,"max_output_tokens":10000}},"result":{"type":"output","value":{"chunk_id":"history","wall_time_seconds":0.12,"exit_code":0,"original_token_count":100,"output":${Json.encodeToString(output)}}}}""",
                ),
            )
            storage.commit(UnstableSnapshot(emptyList()))
            storage.commit(StableAssistantMessage(listOf(ContentItem.OutputText("HISTORY_TOOL_LATEST"))))
        }
    } finally { withContext(NonCancellable) { repository.cancelAndJoin() } }
}

internal fun historyProductStreamParts(): List<String> =
    (0 until 80).map { "HISTORY_STREAM_${it.toString().padStart(3, '0')}\n" }

/** Seed both distinct nullable wire forms before the test's actual Backend opens the Session. */
internal suspend fun CoroutineScope.seedCommandIntentLegacyProductFixture(home: Path) {
    prepareKodexHome(home).closeAndJoin()
    val repository = SqliteKodexSessionRepository(home, testKodexAgentDependencies())
    try {
        val session = repository.create()
        repository.open(session).runtime.modify { storage ->
            storage.initialize(testSettings("Command intent legacy fixture", home))
            storage.commit(UnstableSnapshot(emptyList()))
            storage.commit(StableUserMessage(listOf(ContentItem.InputText("Legacy intent replay fixture"))))
            for ((shape, name) in listOf("missing" to null, "null" to null, "empty" to "", "named" to "Read legacy output")) {
                storage.commit(StableCommandExecution(
                    "intent-legacy-$shape",
                    action = StableCommandExecutionAction.ExecCommand(ExecCommandArguments(
                        command = "LEGACY_COMMAND_$shape", name = name, yieldTimeMillis = 1_000,
                    )),
                    result = StableCommandExecutionResult.Failure("Isolated fixture was not executed"),
                ))
            }
            storage.commit(UnstableSnapshot(emptyList()))
            storage.commit(StableAssistantMessage(listOf(ContentItem.OutputText("LEGACY_INTENT_REPLAY_READY"))))
        }
    } finally { withContext(NonCancellable) { repository.cancelAndJoin() } }
    openSqliteConnection(Path(home, "kodex.db")).use { db ->
        db.execute("""
            UPDATE agent_commits SET payload_json = json_set(payload_json, '$.action.arguments.name', NULL)
            WHERE type = 'command_execution_tool_event' AND json_extract(payload_json, '$.call_id') = 'intent-legacy-null'
        """.trimIndent())
        check(db.changes() == 1)
        db.execute("""
            UPDATE agent_commits SET payload_json = json_remove(payload_json, '$.action.arguments.name')
            WHERE type = 'command_execution_tool_event' AND json_extract(payload_json, '$.call_id') = 'intent-legacy-missing'
        """.trimIndent())
        check(db.changes() == 1)
    }
}
