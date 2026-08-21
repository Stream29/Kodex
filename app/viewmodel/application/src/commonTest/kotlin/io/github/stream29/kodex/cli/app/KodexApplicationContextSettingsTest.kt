package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentcontext.contract.AgentContextSettings
import io.github.stream29.kodex.agentsession.filesystem.FileSystemKodexSessionRepository
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

val kodexApplicationContextSettingsTest by testSuite {
    test("passes the actual data directory as Kodex Home context") {
        val root = temporaryDirectory("application-context-settings")
        val codexDirectory = Path(root, "codex-source")
        val agentsDirectory = Path(root, "agents-home")
        val workingDirectory = Path(root, "working-directory")
        val dataDirectory = Path(root, "custom-kodex-home")
        listOf(codexDirectory, agentsDirectory, workingDirectory, dataDirectory).forEach { directory ->
            SystemCoroutineFileSystem.createDirectories(directory)
        }
        var captured: AgentContextSettings? = null
        val application = try {
            KodexApplication.open(
                codexDirectory = codexDirectory,
                agentsDirectory = agentsDirectory,
                workingDirectory = workingDirectory,
                dataDirectory = dataDirectory,
                sessionRepositoryFactory = { root, dependencies ->
                    captured = dependencies.contextSettings.value
                    FileSystemKodexSessionRepository(root, dependencies)
                },
            )
        } catch (failure: Throwable) {
            deleteRecursively(root)
            throw failure
        }
        try {
            val context = assertNotNull(captured)

            assertEquals(SystemCoroutineFileSystem.resolve(agentsDirectory), context.agentsHome)
            assertEquals(dataDirectory, context.kodexHome)
        } finally {
            application.close()
            deleteRecursively(root)
        }
    }
}

private fun temporaryDirectory(name: String): Path =
    Path(SystemTemporaryDirectory, "kodex-$name-${Random.nextLong()}")

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { child -> deleteRecursively(child) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
