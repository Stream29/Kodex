package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.cli.settings.openBackendSettings
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val mcpConfigurationAdmissionTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("complete RPC settings reject colliding server names before file publication") {
        withServices { services, _ ->
            val initial = services.global.getSettings()
            val configuration = McpServerConfiguration.Stdio("never-run", enabled = false)
            val failure = assertFailsWith<IllegalArgumentException> {
                services.global.compareAndSetSettings(initial, initial.copy(
                    mcpServers = mapOf("a-b" to configuration, "a_b" to configuration),
                ))
            }
            assertTrue(failure.message.orEmpty().contains("Ambiguous MCP server"))
            assertEquals(initial, services.global.getSettings())
            assertTrue(services.global.getMcpServers().isEmpty())
        }
    }

    test("persisted colliding settings fail backend startup honestly without rewriting bytes or acquiring sessions") {
        val home = Path(SystemTemporaryDirectory, "kodex-mcp-old-admission-${Random.nextLong()}")
        try {
            val store = openBackendSettings(home, defaultBackendSettings())
            val configuration = McpServerConfiguration.Stdio("never-run", enabled = false)
            // Deliberately emulate an old file written before route admission.
            store.update { it.copy(mcpServers = mapOf("a-b" to configuration, "a_b" to configuration)) }
            val before = SystemCoroutineFileSystem.readBytes(store.settingsPath)
            assertFailsWith<IllegalArgumentException> {
                withBackendServices(
                    home, Path(home, "codex"), Path(home, "agents"),
                    createLoginClient = { ServicesTestLogin() },
                    createClient = { io.github.stream29.kodex.openai.client.test.mockOpenAiClient {} },
                ) { error("ambiguous settings must not admit a backend") }
            }
            val after = SystemCoroutineFileSystem.readBytes(store.settingsPath)
            assertTrue(before.contentEquals(after))
            assertFalse(SystemCoroutineFileSystem.exists(Path(home, "sessions")))
        } finally {
            suspend fun remove(path: Path) {
                val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
                if (metadata.isDirectory) SystemCoroutineFileSystem.list(path).forEach { remove(it) }
                SystemCoroutineFileSystem.delete(path)
            }
            remove(home)
        }
    }
}
