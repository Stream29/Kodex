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
import io.github.stream29.kodex.utils.osenvironment.environmentVariable
import io.github.stream29.kodex.utils.osenvironment.userHomeDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
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
        config = OpenAiClientConfig(),
    )
    return LiveCatalogFixture(client, OpenAiModelCatalog(client))
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
        val catalog = OpenAiModelCatalog(
            client = mockOpenAiClient {
                listModels { OpenAiResult.Success(ModelsResponse(BuiltInModelCatalog)) }
            },
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
        }
    }

    test("resolves the longest matching model prefix and keeps the requested slug") {
        val catalog = OpenAiModelCatalog(
            client = mockOpenAiClient {
                listModels { OpenAiResult.Success(ModelsResponse(BuiltInModelCatalog)) }
            },
        )
        try {
            val resolved = catalog.resolve(OpenAiModelId("gpt-5.4-mini-preview"))

            assertEquals(OpenAiModelId("gpt-5.4-mini-preview"), resolved.slug)
            assertEquals("GPT-5.4-Mini", resolved.displayName)
        } finally {
            catalog.close()
        }
    }

    test("resolves one provider namespace segment but not multiple segments") {
        val catalog = OpenAiModelCatalog(
            client = mockOpenAiClient {
                listModels { OpenAiResult.Success(ModelsResponse(BuiltInModelCatalog)) }
            },
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
        }
    }

    test("keeps the built-in catalog until the remote refresh completes") {
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
    }

    test("publishes the remote catalog") {
        val freshModel = model("fresh-model")
        val catalog = OpenAiModelCatalog(
            client = mockOpenAiClient {
                listModels { OpenAiResult.Success(ModelsResponse(listOf(freshModel))) }
            },
        )
        try {
            assertEquals(
                listOf(freshModel),
                withTimeout(10.seconds) { catalog.models.first { it == listOf(freshModel) } },
            )
        } finally {
            catalog.close()
        }
    }

    test("close cancels a pending startup refresh") {
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
        )
        try {
            withTimeout(10.seconds) { remoteStarted.await() }
            catalog.close()
            withTimeout(10.seconds) { remoteCancelled.await() }
        } finally {
            catalog.close()
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
