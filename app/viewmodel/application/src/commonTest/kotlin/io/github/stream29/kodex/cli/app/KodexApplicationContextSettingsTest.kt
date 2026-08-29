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
import kotlin.test.assertNull

val kodexApplicationContextSettingsTest by testSuite {
    test("accepts a missing Agents Home and passes the actual context directories") {
        val root = temporaryDirectory("application-context-settings")
        val codexDirectory = Path(root, "codex-source")
        val agentsDirectory = Path(root, "agents-home")
        val workingDirectory = Path(root, "working-directory")
        val dataDirectory = Path(root, "custom-kodex-home")
        listOf(codexDirectory, workingDirectory, dataDirectory).forEach { directory ->
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
            application.viewModel.openSessionCatalogPopup().viewModel.refresh()
            val context = assertNotNull(captured)

            assertEquals(
                Path(SystemCoroutineFileSystem.resolve(root), agentsDirectory.name),
                context.agentsHome,
            )
            assertNull(SystemCoroutineFileSystem.metadataOrNull(agentsDirectory))
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
