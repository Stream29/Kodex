package io.github.stream29.kodex.agentstorage.filesystem

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWebSearchCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSuggestSubagentTaskResult
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskResponse
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.agentstorage.contract.latestIndex
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs

val fileSystemAgentStorageTest by testSuite {
    test("reads an accepted suggestion written before the stable package migration") {
        val root = Path(SystemTemporaryDirectory, "kodex-legacy-suggestion-${Random.nextLong()}")
        try {
            val storage = FileSystemAgentStorage.ofEmpty(root)
            storage.initialize(KodexAgentSettings(OpenAiModelId("test")))
            SystemCoroutineFileSystem.createDirectories(Path(root, IndexDirectory))
            SystemCoroutineFileSystem.writeString(
                Path(root, IndexDirectory, "1.json"),
                """{"type":"suggest_subagent_task_tool_event","call_id":"suggest","arguments":{"tasks":[{"name":"Worker","prompt":"Inspect tests."}]},"result":{"type":"completed","response":{"type":"accepted","sessions":[{"uri":"file:///tmp/synthetic/1","name":"Worker"}],"decision":"accepted"}}}""",
            )
            val reopened = FileSystemAgentStorage(root)
            val event = assertIs<StableSuggestSubagentTaskToolEvent>(reopened.index.getExact(1))
            val response = assertIs<SuggestSubagentTaskResponse.Accepted>(
                assertIs<StableSuggestSubagentTaskResult.Completed>(event.result).response,
            )
            assertEquals("file:///tmp/synthetic/1", response.sessions.single().uri)
            assertEquals("Inspect tests.", event.arguments.tasks.single().prompt)
            assertEquals(null, reopened.work.getExact(1))
        } finally {
            deleteRecursively(SystemCoroutineFileSystem, root)
        }
    }

    test("persists index and work timelines and raw-copies a prefix") {
        assertEquals(
            listOf("index", "work", "settings", "timestamp", "token-count", "unstable"),
            FileSystemAgentStorageTimelineDirectories,
        )
        val root = Path(
            SystemTemporaryDirectory,
            "kodex-agent-storage-${Random.nextLong()}",
        )
        try {
            val sourceDirectory = Path(root, "source")
            val targetDirectory = Path(root, "target")
            val source = FileSystemAgentStorage.ofEmpty(sourceDirectory)
            source.initialize(
                KodexAgentSettings(model = OpenAiModelId("test-model")),
            )
            val message = StableUserMessage(
                content = listOf(ContentItem.InputText("hello")),
            )
            val work = StableWebSearchCall(
                ResponseItem.WebSearchCall(status = "completed"),
            )
            source.index[2] = message
            source.work[2] = work

            val reopened = FileSystemAgentStorage(sourceDirectory)
            assertEquals(null, reopened.index.getExact(0))
            assertEquals(message, reopened.index[2])
            assertEquals(work, reopened.work[2])

            val target = FileSystemAgentStorage.ofEmpty(targetDirectory)
            reopened.forkRawTo(until = 3, target = target)

            assertEquals(2, target.latestIndex())
            assertEquals(message, target.index[2])
            assertEquals(work, target.work[2])
            assertEquals(
                SystemCoroutineFileSystem.readBytes(
                    Path(sourceDirectory, IndexDirectory, "2.json"),
                ).toList(),
                SystemCoroutineFileSystem.readBytes(
                    Path(targetDirectory, IndexDirectory, "2.json"),
                ).toList(),
            )
        } finally {
            deleteRecursively(SystemCoroutineFileSystem, root)
        }
    }
}
