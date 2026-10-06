package io.github.stream29.kodex.rpc.server

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.cli.rpc.RpcGlobalEditor
import io.github.stream29.kodex.cli.rpc.RpcGlobalSettings
import io.github.stream29.kodex.cli.rpc.RpcServices
import io.github.stream29.kodex.cli.rpc.RpcSessionViews
import io.github.stream29.kodex.cli.rpc.SessionViewStatus
import io.github.stream29.kodex.cli.sessiontitle.SessionTitleGenerationResult
import io.github.stream29.kodex.cli.sessiontitle.SessionTitleGenerator
import io.github.stream29.kodex.cli.settings.openCliFrontendSettings
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.inmemory.withInMemoryRpc
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.*

val backendTitleRpcTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("accepted append survives borrowed frontend view and Settings close and updates surviving name observers") {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = CompletableDeferred<Job>()
        val generator = SessionTitleGenerator { _, _, _ ->
            job.complete(currentCoroutineContext().job)
            entered.complete(Unit)
            release.await()
            SessionTitleGenerationResult.Generated("Keep backend naming alive")
        }
        withTitleFrontend(generator) { services, home ->
            val views = RpcSessionViews(this, services, MutableStateFlow(services.global.getModels()))
            val otherViews = RpcSessionViews(this, services, MutableStateFlow(services.global.getModels()))
            val globalSettings = RpcGlobalSettings.open(
                services.global, openCliFrontendSettings(home), this, 80,
            )
            val popup = RpcGlobalEditor(globalSettings, services.global, this)
            try {
                val index = services.global.createSession(KodexAgentSettings(OpenAiModelId("test-model")))
                val origin = views.open(index)
                val other = otherViews.open(index)
                val nonce = services.settings.getCacheNonce(index)
                assertEquals("Session $index", other.current().settings.value.threadName)
                assertEquals(1, origin.current().appendUserMessage(listOf(ContentItem.InputText("Name this accepted message"))))
                entered.await()

                views.release(origin)
                views.close()
                views.join()
                popup.close()
                globalSettings.close()
                globalSettings.join()
                assertEquals(SessionViewStatus.Closed, origin.status.value)
                assertTrue(job.await().isActive)
                assertTrue(services.global.getSessionCatalog(false).single().isActive)

                release.complete(Unit)
                job.await().join()
                // This is the actual tab binding's name projection, backed by the existing
                // SettingsTimelineRpc metadata and value queries, not a test-owned name flow.
                val observed = other.current().settings.first { it.threadName == "Keep backend naming alive" }
                assertEquals("Keep backend naming alive", observed.threadName)
                val tail = services.settings.getLatestIndexFlow(index).first { position ->
                    services.settings.get(index, nonce, position).threadName == observed.threadName
                }
                assertEquals(observed, services.settings.get(index, nonce, tail))
                val catalog = services.global.getSessionCatalog(false).single()
                assertEquals(observed.threadName, catalog.threadName)
                assertTrue(catalog.isActive)
                assertFalse(catalog.running)
                assertEquals(2, services.runtime.getLatestIndex(index))
            } finally {
                release.complete(Unit)
                withContext(NonCancellable) {
                    popup.close()
                    globalSettings.close()
                    views.close()
                    otherViews.close()
                    globalSettings.join()
                    views.join()
                    otherViews.join()
                }
            }
        }
    }

    test("real SettingsTimeline CAS rename defeats delayed output without repeating generation") {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = CompletableDeferred<Job>()
        var calls = 0
        withTitleFrontend(SessionTitleGenerator { _, _, _ ->
            calls++
            job.complete(currentCoroutineContext().job)
            entered.complete(Unit)
            release.await()
            SessionTitleGenerationResult.Generated("Late automatic title")
        }) { services, _ ->
            val views = RpcSessionViews(this, services, MutableStateFlow(services.global.getModels()))
            try {
                val index = services.global.createSession(KodexAgentSettings(OpenAiModelId("test-model")))
                val view = views.open(index)
                assertEquals(1, view.current().appendUserMessage(listOf(ContentItem.InputText("First text"))))
                entered.await()
                val nonce = services.settings.getCacheNonce(index)
                val old = services.settings.get(index, nonce, services.runtime.getLatestIndex(index))
                assertTrue(view.current().settings.compareAndSet(old, old.copy(threadName = "Explicit name wins")))
                release.complete(Unit)
                job.await().join()
                view.current().settings.first { it.threadName == "Explicit name wins" }
                assertEquals("Explicit name wins", services.global.getSessionCatalog(false).single().threadName)
                view.current().appendUserMessage(listOf(ContentItem.InputText("Second text")))
                assertEquals(1, calls)
            } finally {
                release.complete(Unit)
                withContext(NonCancellable) {
                    views.close()
                    views.join()
                }
            }
        }
    }

    test("RPC history replacement rejects cancellation-insensitive late output and reopens first-text eligibility") {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val oldJob = CompletableDeferred<Job>()
        var calls = 0
        withTitleFrontend(SessionTitleGenerator { _, _, _ ->
            calls++
            if (calls == 1) {
                oldJob.complete(currentCoroutineContext().job)
                entered.complete(Unit)
                // Simulate a supplier that completes despite cancellation: the attempt fence,
                // not merely supplier cancellation, must reject this now-obsolete result.
                withContext(NonCancellable) { release.await() }
                SessionTitleGenerationResult.Generated("Obsolete generated name")
            } else {
                SessionTitleGenerationResult.Generated("Name replacement history")
            }
        }) { services, _ ->
            val views = RpcSessionViews(this, services, MutableStateFlow(services.global.getModels()))
            try {
                val index = services.global.createSession(KodexAgentSettings(OpenAiModelId("test-model")))
                val view = views.open(index)
                assertEquals(1, view.current().appendUserMessage(listOf(ContentItem.InputText("Obsolete text"))))
                entered.await()
                val oldNonce = services.index.getCacheNonce(index)
                view.current().revertHistory(1, oldNonce)
                assertNotEquals(oldNonce, services.index.getCacheNonce(index))
                release.complete(Unit)
                oldJob.await().join()
                assertEquals(0, services.runtime.getLatestIndex(index))
                val nonce = services.settings.getCacheNonce(index)
                assertEquals("Session $index", services.settings.get(index, nonce, 0).threadName)
                assertEquals("Session $index", services.global.getSessionCatalog(false).single().threadName)
                assertEquals(1, view.current().appendUserMessage(listOf(ContentItem.InputText("Replacement text"))))
                view.current().settings.first { it.threadName == "Name replacement history" }
                assertEquals("Name replacement history", services.global.getSessionCatalog(false).single().threadName)
                assertEquals(2, calls)
            } finally {
                release.complete(Unit)
                withContext(NonCancellable) {
                    views.close()
                    views.join()
                }
            }
        }
    }
}

/** Only external suppliers are mocked; Session, runtime, storage and all eight RPC services are real. */
private suspend fun withTitleFrontend(
    generator: SessionTitleGenerator,
    block: suspend CoroutineScope.(RpcServices, Path) -> Unit,
) {
    val home = Path(SystemTemporaryDirectory, "kodex-title-frontend-${Random.nextLong()}")
    try {
        withServices(titleGenerator = generator) { backend, _ ->
            withInMemoryRpc(backend::register) { raw ->
                block(RpcServices(RestoringRpcClient(raw)), home)
            }
        }
    } finally {
        suspend fun remove(path: Path) {
            val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
            if (metadata.isDirectory) SystemCoroutineFileSystem.list(path).forEach { remove(it) }
            SystemCoroutineFileSystem.delete(path)
        }
        withContext(NonCancellable) { remove(home) }
    }
}
