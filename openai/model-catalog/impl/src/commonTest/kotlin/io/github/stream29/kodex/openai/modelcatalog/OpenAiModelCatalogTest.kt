package io.github.stream29.kodex.openai.modelcatalog

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.ModelsResponse
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiResult
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.ReasoningEffortPreset
import io.github.stream29.kodex.openai.contextWindowTokenStatus
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
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
                ReasoningEffort.Max,
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

    test("normalizes duplicate remote reasoning presets at startup and explicit refresh") {
        val providerMax = ReasoningEffortPreset(ReasoningEffort.Max, "Provider Max")
        val legacyUltra = ReasoningEffortPreset(ReasoningEffort.Max, "Legacy Ultra")
        val future = ReasoningEffortPreset(ReasoningEffort.Custom("future"), "Future")
        val remoteModel = model("normalized-model").copy(
            defaultReasoningLevel = ReasoningEffort.Max,
            supportedReasoningLevels = listOf(providerMax, legacyUltra, future),
        )
        val expectedModel = remoteModel.copy(
            supportedReasoningLevels = listOf(providerMax, future),
        )
        val catalog = OpenAiModelCatalog(
            client = mockOpenAiClient {
                listModels { OpenAiResult.Success(ModelsResponse(listOf(remoteModel))) }
            },
        )
        try {
            val startupModels = withTimeout(10.seconds) {
                catalog.models.first { models ->
                    models.singleOrNull()?.slug == remoteModel.slug
                }
            }
            assertEquals(listOf(expectedModel), startupModels)

            assertEquals(listOf(expectedModel), catalog.refresh())
            assertEquals(listOf(expectedModel), catalog.models.value)
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

}
