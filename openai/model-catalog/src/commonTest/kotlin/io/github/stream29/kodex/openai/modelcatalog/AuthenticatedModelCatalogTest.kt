@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.openai.modelcatalog

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.ModelServiceTier
import io.github.stream29.kodex.openai.ModelsResponse
import io.github.stream29.kodex.openai.OpenAiAuthState
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiResult
import io.github.stream29.kodex.openai.OpenAiSubscriptionAuthState
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.openai.availableServiceTiers
import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class CatalogAuth(initial: OpenAiAuthState) : OpenAiAuthStore {
    override val state = MutableStateFlow(initial)
}

private fun authenticated(account: String, token: String = account): OpenAiAuthState.Authenticated =
    OpenAiAuthState.Authenticated(OpenAiSubscriptionAuthState(token, accountId = account))

private fun remoteModel(ultrafast: Boolean = false, slug: String = "gpt-6.1-sol"): ModelInfo =
    ModelInfo(
        slug = OpenAiModelId(slug),
        displayName = slug,
        serviceTiers = if (ultrafast) {
            listOf(ModelServiceTier("ultrafast", "Ultrafast", "Remote capability"))
        } else {
            emptyList()
        },
    )

val authenticatedModelCatalogTest by testSuite(testConfig = TestConfig.testScope(isEnabled = false)) {
    test("login and account switches refresh tiers without reopening the catalog") {
        runTest {
            val auth = CatalogAuth(OpenAiAuthState.Unavailable.CredentialsNotFound)
            var calls = 0
            val client = mockOpenAiClient {
                listModels {
                    calls++
                    val account = (auth.state.value as OpenAiAuthState.Authenticated).credentials.accountId
                    OpenAiResult.Success(ModelsResponse(listOf(remoteModel(ultrafast = account == "max"))))
                }
            }
            val catalog = backgroundScope.ownedOpenAiModelCatalog(client, auth)
            try {
                runCurrent()
                assertEquals(0, calls)
                assertEquals(BuiltInModelCatalog, catalog.models.value)
                auth.state.value = authenticated("lite")
                runCurrent()
                assertEquals(listOf(ServiceTier.Default), catalog.models.value.single().availableServiceTiers())
                auth.state.value = authenticated("max")
                runCurrent()
                assertEquals(
                    listOf(ServiceTier.Default, ServiceTier.Ultrafast),
                    catalog.models.value.single().availableServiceTiers(),
                )
                auth.state.value = authenticated("lite")
                runCurrent()
                assertEquals(listOf(ServiceTier.Default), catalog.models.value.single().availableServiceTiers())
                auth.state.value = OpenAiAuthState.Unavailable.CredentialsNotFound
                runCurrent()
                assertEquals(BuiltInModelCatalog, catalog.models.value)
                assertEquals(3, calls)
            } finally {
                catalog.close()
            }
        }
    }

    test("mapLatest cancels the previous account request and publishes the new catalog") {
        runTest {
            val auth = CatalogAuth(authenticated("old"))
            val cancelled = CompletableDeferred<Unit>()
            val fresh = listOf(remoteModel(ultrafast = true))
            val client = mockOpenAiClient {
                listModels {
                    if ((auth.state.value as OpenAiAuthState.Authenticated).credentials.accountId == "old") {
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled.complete(Unit)
                        }
                    }
                    OpenAiResult.Success(ModelsResponse(fresh))
                }
            }
            val catalog = backgroundScope.ownedOpenAiModelCatalog(client, auth)
            try {
                runCurrent()
                auth.state.value = authenticated("new")
                runCurrent()
                assertTrue(cancelled.isCompleted)
                assertEquals(fresh, catalog.models.value)
            } finally {
                catalog.close()
            }
        }
    }

    test("an obsolete request that returns after cancellation cannot publish its result") {
        runTest {
            val auth = CatalogAuth(authenticated("old"))
            val releaseOld = CompletableDeferred<Unit>()
            val cancellationSeen = CompletableDeferred<Unit>()
            val old = listOf(remoteModel(slug = "obsolete-account"))
            val fresh = listOf(remoteModel(ultrafast = true))
            val client = mockOpenAiClient {
                listModels {
                    if ((auth.state.value as OpenAiAuthState.Authenticated).credentials.accountId == "old") {
                        try {
                            awaitCancellation()
                        } catch (_: CancellationException) {
                            cancellationSeen.complete(Unit)
                            withContext(NonCancellable) { releaseOld.await() }
                        }
                        OpenAiResult.Success(ModelsResponse(old))
                    } else {
                        OpenAiResult.Success(ModelsResponse(fresh))
                    }
                }
            }
            val catalog = backgroundScope.ownedOpenAiModelCatalog(client, auth)
            val observed = mutableListOf<List<ModelInfo>>()
            backgroundScope.launch { catalog.models.collect { observed += it } }
            try {
                runCurrent()
                auth.state.value = authenticated("new")
                runCurrent()
                assertTrue(cancellationSeen.isCompleted)
                releaseOld.complete(Unit)
                runCurrent()
                assertEquals(fresh, catalog.models.value)
                assertFalse(old in observed)
            } finally {
                releaseOld.complete(Unit)
                catalog.close()
            }
        }
    }

    test("failure retains only the same account snapshot and does not end auth observation") {
        runTest {
            val auth = CatalogAuth(authenticated("first"))
            val first = listOf(remoteModel(slug = "first-account"))
            val second = listOf(remoteModel(ultrafast = true))
            val nextFailure = CompletableDeferred<Unit>()
            val client = mockOpenAiClient {
                listModels {
                    val credentials = (auth.state.value as OpenAiAuthState.Authenticated).credentials
                    when (credentials.accessToken) {
                        "first" -> OpenAiResult.Success(ModelsResponse(first))
                        "same-account-refresh" -> error("Synthetic same-account failure")
                        "new-account-failure" -> {
                            nextFailure.await()
                            error("Synthetic new-account failure")
                        }
                        else -> OpenAiResult.Success(ModelsResponse(second))
                    }
                }
            }
            val catalog = backgroundScope.ownedOpenAiModelCatalog(client, auth)
            try {
                runCurrent()
                assertEquals(first, catalog.models.value)
                auth.state.value = authenticated("first", "same-account-refresh")
                runCurrent()
                assertEquals(first, catalog.models.value)
                auth.state.value = authenticated("second", "new-account-failure")
                runCurrent()
                assertEquals(BuiltInModelCatalog, catalog.models.value)
                nextFailure.complete(Unit)
                runCurrent()
                assertEquals(BuiltInModelCatalog, catalog.models.value)
                auth.state.value = authenticated("second", "recovered")
                runCurrent()
                assertEquals(second, catalog.models.value)
            } finally {
                nextFailure.complete(Unit)
                catalog.close()
            }
        }
    }

    test("explicit refresh rejects an account change while its response is in flight") {
        runTest {
            val auth = CatalogAuth(authenticated("first"))
            val releaseManual = CompletableDeferred<Unit>()
            val stale = listOf(remoteModel(slug = "manual-old-account"))
            val fresh = listOf(remoteModel(ultrafast = true))
            var calls = 0
            val client = mockOpenAiClient {
                listModels {
                    calls++
                    if (calls == 2) {
                        releaseManual.await()
                        OpenAiResult.Success(ModelsResponse(stale))
                    } else {
                        OpenAiResult.Success(ModelsResponse(fresh))
                    }
                }
            }
            val catalog = backgroundScope.ownedOpenAiModelCatalog(client, auth)
            try {
                runCurrent()
                val manual = async { catalog.refresh() }
                runCurrent()
                assertEquals(2, calls)
                auth.state.value = authenticated("second")
                runCurrent()
                assertEquals(3, calls)
                releaseManual.complete(Unit)
                runCurrent()
                assertFailsWith<CancellationException> { manual.await() }
                assertEquals(fresh, catalog.models.value)
            } finally {
                releaseManual.complete(Unit)
                catalog.close()
            }
        }
    }

    test("closing the catalog cancels its active request and stops authentication observation") {
        runTest {
            val auth = CatalogAuth(authenticated("first"))
            val cancelled = CompletableDeferred<Unit>()
            var calls = 0
            val client = mockOpenAiClient {
                listModels {
                    calls++
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            }
            val catalog = backgroundScope.ownedOpenAiModelCatalog(client, auth)
            try {
                runCurrent()
                catalog.close()
                runCurrent()
                assertTrue(cancelled.isCompleted)
                auth.state.value = authenticated("second")
                runCurrent()
                assertEquals(1, calls)
            } finally {
                catalog.close()
            }
        }
    }
}
