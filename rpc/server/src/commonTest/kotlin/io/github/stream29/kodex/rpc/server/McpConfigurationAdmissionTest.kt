package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.utils.mq.InMemoryMqBroker
import io.github.stream29.kodex.utils.stream.InMemoryStreamService

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
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
    test("complete RPC settings reject newly added reserved CLI server names while allowing a-b and a_b") {
        withServices { services, _ ->
            val initial = services.global.getSettings()
            val configuration = McpServerConfiguration.Stdio("never-run", enabled = false)
            val failure = assertFailsWith<IllegalArgumentException> {
                services.global.compareAndSetSettings(
                    initial,
                    initial.copy(mcpServers = mapOf("terminal" to configuration)),
                )
            }
            assertTrue(failure.message.orEmpty().contains("reserved"))
            assertEquals(initial, services.global.getSettings())
            assertTrue(services.global.getMcpServers().isEmpty())

            assertTrue(
                services.global.compareAndSetSettings(
                    initial,
                    initial.copy(mcpServers = mapOf("a-b" to configuration, "a_b" to configuration)),
                ),
            )
            assertEquals(setOf("a-b", "a_b"), services.global.getSettings().mcpServers.keys)
        }
    }

    test("legacy persisted reserved MCP server loads and remains editable while blank names fail startup") {
        val home = Path(SystemTemporaryDirectory, "kodex-mcp-reserved-admission-${Random.nextLong()}")
        try {
            val store = io.github.stream29.kodex.cli.settings.UnifiedSettingsStore.open(home, defaultBackendSettings()).backendStore
            val configuration = McpServerConfiguration.Stdio("never-run", enabled = false)
            store.update { it.copy(mcpServers = mapOf("terminal" to configuration)) }
            withBackendServices(
                home,
                Path(home, "codex"),
                Path(home, "agents"),
                createLoginClient = { ServicesTestLogin() },
                createClient = { io.github.stream29.kodex.openai.client.test.mockOpenAiClient {} },
                broker = InMemoryMqBroker(),
                streamService = InMemoryStreamService(),
            ) { services ->
                val loaded = services.global.getSettings()
                assertEquals(setOf("terminal"), loaded.mcpServers.keys)
                assertTrue(
                    services.global.compareAndSetSettings(
                        loaded,
                        loaded.copy(mcpServers = emptyMap()),
                    ),
                )
                assertTrue(services.global.getSettings().mcpServers.isEmpty())
            }

            val settingsPath = Path(home, "settings.yml")
            val invalidYaml = "backend:\n  mcp_servers:\n    \" \":\n      type: stdio\n      command: never-run\n      enabled: false\n"
            SystemCoroutineFileSystem.writeString(settingsPath, invalidYaml)
            val before = SystemCoroutineFileSystem.readBytes(settingsPath)
            assertFailsWith<IllegalArgumentException> {
                withBackendServices(
                    home,
                    Path(home, "codex"),
                    Path(home, "agents"),
                    createLoginClient = { ServicesTestLogin() },
                    createClient = { io.github.stream29.kodex.openai.client.test.mockOpenAiClient {} },
                    broker = InMemoryMqBroker(),
                    streamService = InMemoryStreamService(),
                ) { error("blank MCP server name must not admit a backend") }
            }
            val after = SystemCoroutineFileSystem.readBytes(settingsPath)
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
