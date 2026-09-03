package io.github.stream29.kodex.agentstorage.filesystem

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.index.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.work.StableWebSearchCall
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

val fileSystemAgentStorageTest by testSuite {
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
