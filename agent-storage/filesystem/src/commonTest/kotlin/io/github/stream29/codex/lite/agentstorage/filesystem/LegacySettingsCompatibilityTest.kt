package io.github.stream29.codex.lite.agentstorage.filesystem

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals

val legacySettingsCompatibilityTest by testSuite {
    test("legacy persisted tools are ignored while reading settings") {
        val root = Path(
            SystemTemporaryDirectory,
            "codex-lite-legacy-settings-${Random.nextLong()}",
        )
        try {
            val settingsDirectory = Path(root, SettingsDirectory)
            SystemCoroutineFileSystem.createDirectories(settingsDirectory)
            SystemCoroutineFileSystem.writeString(
                Path(settingsDirectory, "0.json"),
                """{"model":"test-model","tools":[{"type":"web_search"}]}""",
            )

            val storage = FileSystemAgentStorage(root)

            assertEquals(OpenAiModelId("test-model"), storage.settings[0].model)
            assertEquals(Path("."), storage.settings[0].cwd)
        } finally {
            deleteRecursively(SystemCoroutineFileSystem, root)
        }
    }
}
