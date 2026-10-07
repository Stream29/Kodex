package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val backendStartupTest by testSuite(compartment = { TestCompartment.RealTime }) {
    for (firstAccess in listOf("catalog", "create")) {
        test("settings auth model and global startup leave sessions absent until $firstAccess") {
            withTimeout(30.seconds) {
                val home = Path(SystemTemporaryDirectory, "kodex-backend-startup-${Random.nextLong()}")
                val sessions = Path(home, "sessions")
                try {
                    withBackendServices(
                        home, Path(home, "codex"), Path(home, "agents"),
                        createLoginClient = { ServicesTestLogin() },
                        createClient = {
                            mockOpenAiClient {
                                listModels { OpenAiResult.Success(ModelsResponse(emptyList())) }
                            }
                        },
                    ) { services ->
                        services.global.getSettings()
                        services.global.getAuthentication()
                        services.global.getModels()
                        services.global.getMcpServers()
                        assertNull(SystemCoroutineFileSystem.metadataOrNull(sessions))
                        if (firstAccess == "catalog") {
                            assertEquals(emptyList(), services.global.getSessionCatalog(false))
                        } else {
                            assertEquals(0, services.global.createSession(KodexAgentSettings(OpenAiModelId("test-model"))))
                        }
                        assertTrue(SystemCoroutineFileSystem.metadataOrNull(sessions)?.isDirectory == true)
                    }
                    if (firstAccess == "create") {
                        // Reopening the same persisted entry uses the new host's
                        // one on-demand repository, not a per-tab root.
                        withBackendServices(
                            home, Path(home, "codex"), Path(home, "agents"),
                            createLoginClient = { ServicesTestLogin() },
                            createClient = {
                                mockOpenAiClient {
                                    listModels { OpenAiResult.Success(ModelsResponse(emptyList())) }
                                }
                            },
                        ) { services ->
                            services.global.keepSessionAlive(0)
                            assertEquals(0, services.runtime.getLatestIndex(0))
                            assertTrue(services.global.getSessionCatalog(false).single().isActive)
                        }
                    }
                } finally {
                    removeStartupTree(home)
                }
            }
        }
    }
}

private suspend fun removeStartupTree(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) SystemCoroutineFileSystem.list(path).forEach { removeStartupTree(it) }
    SystemCoroutineFileSystem.delete(path)
}
