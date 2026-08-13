package io.github.stream29.kodex.openai.modelcatalog

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.ModelsResponse
import io.github.stream29.kodex.openai.OpenAiSubscriptionAuthState
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiResult
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.contextWindowTokenStatus
import io.github.stream29.kodex.openai.client.OpenAiClient
import io.github.stream29.kodex.openai.client.test.InMemoryOpenAiAuthStore
import io.github.stream29.kodex.openai.client.OpenAiClientConfig
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.openai.codexclistorage.CodexCliStorage
import io.github.stream29.kodex.openai.codexclistorage.CodexAuthJson
import io.github.stream29.kodex.openai.codexclistorage.CodexModelsCache
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.osenvironment.environmentVariable
import io.github.stream29.kodex.utils.osenvironment.userHomeDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.encodeToString
import kotlin.random.Random
import kotlin.time.Instant
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

private fun model(
    slug: String,
    contextWindow: Long? = 272_000L,
    maxContextWindow: Long? = contextWindow,
    autoCompactionTokenLimit: Long? = null,
    effectiveContextWindowPercent: Long = 95L,
): ModelInfo =
    ModelInfo(
        slug = OpenAiModelId(slug),
        displayName = slug,
        contextWindow = contextWindow,
        maxContextWindow = maxContextWindow,
        autoCompactionTokenLimit = autoCompactionTokenLimit,
        effectiveContextWindowPercent = effectiveContextWindowPercent,
    )

private suspend fun temporaryRoot(): Path =
    Path(SystemTemporaryDirectory, "kodex-model-catalog-${Random.nextLong()}").also {
        SystemCoroutineFileSystem.createDirectories(it)
    }

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        for (child in SystemCoroutineFileSystem.list(path)) {
            deleteRecursively(child)
        }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}

private suspend fun writeCodexModelsCache(
    directory: Path,
    cache: CodexModelsCache,
) {
    SystemCoroutineFileSystem.createDirectories(directory)
    SystemCoroutineFileSystem.writeString(
        Path(directory, "models_cache.json"),
        OpenAiJsonCodec.encodeToString(CodexModelsCache.serializer(), cache),
    )
}

private fun testCodexDirectory(): Path =
    environmentVariable("CODEX_HOME")
        ?.takeIf(String::isNotBlank)
        ?.let(::Path)
        ?: userHomeDirectory()?.let { home -> Path(home, ".codex") }
        ?: error("CODEX_HOME or a readable user home directory must be set for model-catalog tests.")

private suspend fun liveCatalog(): LiveCatalogFixture {
    val storage = CodexCliStorage(testCodexDirectory())
    val client = OpenAiClient(
        authStore = InMemoryOpenAiAuthStore(storage.readAuthOrNull().toSubscriptionAuthStateOrThrow()),
        config = OpenAiClientConfig(
            clientVersion = storage.readModelsCacheOrNull()?.clientVersion
                ?.takeIf { it.matches(Regex("""\d+\.\d+\.\d+""")) }
                ?: "0.1.0",
        ),
    )
    return LiveCatalogFixture(client, OpenAiModelCatalog(client, storage))
}

private fun CodexAuthJson?.toSubscriptionAuthStateOrThrow(): OpenAiSubscriptionAuthState {
    val tokens = this?.tokens ?: error("Codex CLI auth tokens are required.")
    return OpenAiSubscriptionAuthState(
        accessToken = tokens.accessToken,
        accountId = tokens.accountId?.takeIf(String::isNotBlank),
    )
}

private class LiveCatalogFixture(
    private val client: OpenAiClient,
    val catalog: OpenAiModelCatalog,
) : AutoCloseable {
    override fun close() {
        catalog.close()
        client.close()
    }
}

val openAiModelCatalogTest by testSuite(testConfig = TestConfig.testScope(isEnabled = false)) {
    test("starts with the bundled Codex model catalog") {
        val root = temporaryRoot()
        val catalog = OpenAiModelCatalog(
            client = mockOpenAiClient {
                listModels { OpenAiResult.Success(ModelsResponse(BuiltInModelCatalog)) }
            },
            codexCliStorage = CodexCliStorage(root),
        )
        try {
            assertEquals(
                listOf(
                    "gpt-5.6-sol",
                    "gpt-5.6-terra",
                    "gpt-5.6-luna",
                    "gpt-5.5",
                    "gpt-5.4",
                    "gpt-5.4-mini",
                    "gpt-5.2",
                    "codex-auto-review",
                ),
                catalog.models.value.map { it.slug.value },
            )
            assertEquals(1_000_000L, catalog.resolve(OpenAiModelId("gpt-5.4")).maxContextWindow)
            assertEquals(ReasoningEffort.Low, catalog.resolve(OpenAiModelId("gpt-5.6-sol")).defaultReasoningLevel)
            assertEquals(
                ReasoningEffort.Ultra,
                catalog.resolve(OpenAiModelId("gpt-5.6-sol")).supportedReasoningLevels.last().effort,
            )
        } finally {
            catalog.close()
            deleteRecursively(root)
        }
    }

    test("resolves the longest matching model prefix and keeps the requested slug") {
        val root = temporaryRoot()
        val catalog = OpenAiModelCatalog(
            client = mockOpenAiClient {
                listModels { OpenAiResult.Success(ModelsResponse(BuiltInModelCatalog)) }
            },
            codexCliStorage = CodexCliStorage(root),
        )
        try {
            val resolved = catalog.resolve(OpenAiModelId("gpt-5.4-mini-preview"))

            assertEquals(OpenAiModelId("gpt-5.4-mini-preview"), resolved.slug)
            assertEquals("GPT-5.4-Mini", resolved.displayName)
        } finally {
            catalog.close()
            deleteRecursively(root)
        }
    }

    test("resolves one provider namespace segment but not multiple segments") {
        val root = temporaryRoot()
        val catalog = OpenAiModelCatalog(
            client = mockOpenAiClient {
                listModels { OpenAiResult.Success(ModelsResponse(BuiltInModelCatalog)) }
            },
            codexCliStorage = CodexCliStorage(root),
        )
        try {
            assertEquals(
                "GPT-5.4-Mini",
                catalog.resolve(OpenAiModelId("provider/gpt-5.4-mini")).displayName,
            )
            assertEquals(
                "provider/nested/gpt-5.4-mini",
                catalog.resolve(OpenAiModelId("provider/nested/gpt-5.4-mini")).displayName,
            )
        } finally {
            catalog.close()
            deleteRecursively(root)
        }
    }

    test("loads the CLI cache before refreshing the remote catalog") {
        val root = temporaryRoot()
        try {
            val storage = CodexCliStorage(root)
            val cachedModel = model("cached-model", contextWindow = 128_000L)
            val remoteModel = model("remote-model", contextWindow = 256_000L)
            val cache = CodexModelsCache(
                fetchedAt = Instant.fromEpochSeconds(0),
                clientVersion = "0.1.0",
                models = listOf(cachedModel),
            )
            writeCodexModelsCache(root, cache)
            val remoteStarted = CompletableDeferred<Unit>()
            val continueRemote = CompletableDeferred<Unit>()
            val catalog = OpenAiModelCatalogImpl(
                client = mockOpenAiClient {
                    listModels {
                        remoteStarted.complete(Unit)
                        continueRemote.await()
                        OpenAiResult.Success(ModelsResponse(listOf(remoteModel)))
                    }
                },
                codexCliStorage = storage,
            )
            try {
                assertEquals(
                    listOf(cachedModel),
                    withTimeout(10.seconds) { catalog.models.first { it == listOf(cachedModel) } },
                )
                withTimeout(10.seconds) { remoteStarted.await() }
                assertEquals(cache, catalog.refreshFromCodexCliCache())
                continueRemote.complete(Unit)
                assertEquals(
                    listOf(remoteModel),
                    withTimeout(10.seconds) { catalog.models.first { it == listOf(remoteModel) } },
                )
            } finally {
                continueRemote.complete(Unit)
                catalog.close()
            }
        } finally {
            deleteRecursively(root)
        }
    }

    test("returns an empty CLI cache and publishes its empty catalog") {
        val root = temporaryRoot()
        try {
            val storage = CodexCliStorage(root)
            val cache = CodexModelsCache(
                fetchedAt = Instant.fromEpochSeconds(0),
                clientVersion = "0.1.0",
                models = emptyList(),
            )
            writeCodexModelsCache(root, cache)
            val catalog = OpenAiModelCatalogImpl(
                client = mockOpenAiClient {
                    listModels { OpenAiResult.Success(ModelsResponse()) }
                },
                codexCliStorage = storage,
            )
            try {
                assertEquals(cache, catalog.refreshFromCodexCliCache())
                assertEquals(emptyList(), catalog.models.value)
            } finally {
                catalog.close()
            }
        } finally {
            deleteRecursively(root)
        }
    }

    test("publishes an empty remote catalog after an absent cache") {
        val root = temporaryRoot()
        try {
            val remoteStarted = CompletableDeferred<Unit>()
            val continueRemote = CompletableDeferred<Unit>()
            val catalog = OpenAiModelCatalog(
                client = mockOpenAiClient {
                    listModels {
                        remoteStarted.complete(Unit)
                        continueRemote.await()
                        OpenAiResult.Success(ModelsResponse())
                    }
                },
                codexCliStorage = CodexCliStorage(root),
            )
            try {
                withTimeout(10.seconds) { remoteStarted.await() }
                assertEquals(BuiltInModelCatalog, catalog.models.value)
                continueRemote.complete(Unit)
                assertEquals(
                    emptyList(),
                    withTimeout(10.seconds) { catalog.models.first { it.isEmpty() } },
                )
            } finally {
                continueRemote.complete(Unit)
                catalog.close()
            }
        } finally {
            deleteRecursively(root)
        }
    }

    test("publishes the remote catalog") {
        val root = temporaryRoot()
        val freshModel = model("fresh-model")
        val catalog = OpenAiModelCatalog(
            client = mockOpenAiClient {
                listModels { OpenAiResult.Success(ModelsResponse(listOf(freshModel))) }
            },
            codexCliStorage = CodexCliStorage(root),
        )
        try {
            assertEquals(
                listOf(freshModel),
                withTimeout(10.seconds) { catalog.models.first { it == listOf(freshModel) } },
            )
        } finally {
            catalog.close()
            deleteRecursively(root)
        }
    }

    test("close cancels a pending startup refresh") {
        val root = temporaryRoot()
        val remoteStarted = CompletableDeferred<Unit>()
        val remoteCancelled = CompletableDeferred<Unit>()
        val catalog = OpenAiModelCatalog(
            client = mockOpenAiClient {
                listModels {
                    remoteStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        remoteCancelled.complete(Unit)
                    }
                }
            },
            codexCliStorage = CodexCliStorage(root),
        )
        try {
            withTimeout(10.seconds) { remoteStarted.await() }
            catalog.close()
            withTimeout(10.seconds) { remoteCancelled.await() }
        } finally {
            catalog.close()
            deleteRecursively(root)
        }
    }

    test("calculates the smaller auto-compaction and context-window budget") {
        val status = model(
            slug = "budget-model",
            contextWindow = 1_000L,
            autoCompactionTokenLimit = 850L,
            effectiveContextWindowPercent = 95L,
        ).contextWindowTokenStatus(
            activeContextTokens = 800L,
            configuredAutoCompactionTokenLimit = null,
        )

        assertEquals(850L, status.autoCompactionTokenLimit)
        assertEquals(950L, status.effectiveContextWindow)
        assertEquals(50L, status.tokensUntilCompaction)
    }

    testFixture { liveCatalog() } closeWith { close() } asParameterForEach {
        test(
            "refreshes model metadata from the real Codex endpoint",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 120.seconds),
        ) { fixture ->
            val models = withContext(Dispatchers.Default) {
                fixture.catalog.refresh()
            }

            assertEquals(models, fixture.catalog.models.value)
        }
    }
}
