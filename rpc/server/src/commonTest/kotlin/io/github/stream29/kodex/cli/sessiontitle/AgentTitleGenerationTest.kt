package io.github.stream29.kodex.cli.sessiontitle

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.agentstorage.contract.getSettingsAt
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val agentTitleGenerationTest by testSuite(compartment = { TestCompartment.RealTime }) {
    testFixture {
        testSuiteCoroutineScope.supervisorChildScope()
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
        test("image-only and blank text leave eligibility for the first accepted nonblank text") {
            val root = initializedTitleSession()
            val captured = CompletableDeferred<String>()
            val job = CompletableDeferred<Job>()
            var calls = 0
            val generator = SessionTitleGenerator { text, model, effort ->
                calls++
                captured.complete(text)
                job.complete(currentCoroutineContext().job)
                assertEquals(OpenAiModelId("configured-title-model"), model)
                assertEquals(ReasoningEffort.High, effort)
                SessionTitleGenerationResult.Generated("Name accepted user text")
            }
            var currentSettings = SessionTitleSettings(enabled = true)
            attachSessionTitleGeneration(
                scope = root.runtime,
                agentState = root.runtime,
                settings = { currentSettings },
                generator = generator,
            )
            for (content in listOf(
                listOf(ContentItem.InputText(" \n ")),
                listOf(ContentItem.InputImage("data:image/png;base64,AA==")),
            )) {
                root.runtime.appendUserMessage(content)
                yield()
            }
            assertEquals(0, calls)
            currentSettings = SessionTitleSettings(
                enabled = true,
                model = OpenAiModelId("configured-title-model"),
                reasoningEffort = ReasoningEffort.High,
            )
            val content = listOf(
                ContentItem.InputText(" "),
                ContentItem.InputImage("data:image/png;base64,AA=="),
                ContentItem.InputText("Use the first nonblank text"),
                ContentItem.InputText("Not a combined prompt"),
            )
            root.runtime.appendUserMessage(content)
            assertEquals("Use the first nonblank text", withTimeout(5.seconds) { captured.await() })
            job.await().join()
            withTimeout(5.seconds) {
                while (root.storage.getSettingsAt(root.runtime.latestIndex.value).name != "Name accepted user text") {
                    delay(10)
                }
            }
            root.runtime.appendUserMessage(listOf(ContentItem.InputText("Second message")))
            yield()
            assertEquals(1, calls)
        }

        test("disabled and nondefault names consume the first accepted text without requesting a title") {
            for (customName in listOf(false, true)) {
                val root = initializedTitleSession { if (customName) "Chosen initial name" else "Session $it" }
                var titleSettings = SessionTitleSettings(enabled = customName)
                val generator = SessionTitleGenerator { _, _, _ -> error("This gate must not call the provider") }
                attachSessionTitleGeneration(
                    scope = root.runtime,
                    agentState = root.runtime,
                    settings = { titleSettings },
                    generator = generator,
                )
                root.runtime.appendUserMessage(listOf(ContentItem.InputText("Accepted first text")))
                yield()
                titleSettings = SessionTitleSettings(enabled = true)
                val current = root.storage.getSettingsAt(root.runtime.latestIndex.value)
                assertTrue(root.runtime.compareAndSetSettings(current, current.copy(name = "Session 99")))
                root.runtime.appendUserMessage(listOf(ContentItem.InputText("Second text after re-enable")))
                yield()
                assertEquals("Session 99", root.storage.getSettingsAt(root.runtime.latestIndex.value).name)
            }
        }

        test("rejected and throwing title requests leave the accepted message intact and do not retry") {
            for (throws in listOf(false, true)) {
                val root = initializedTitleSession()
                val job = CompletableDeferred<Job>()
                var calls = 0
                val generator = SessionTitleGenerator { _, _, _ ->
                    calls++
                    job.complete(currentCoroutineContext().job)
                    if (throws) error("Synthetic optional generation failure")
                    SessionTitleGenerationResult.Rejected("No usable title")
                }
                attachSessionTitleGeneration(
                    scope = root.runtime,
                    agentState = root.runtime,
                    settings = { SessionTitleSettings(enabled = true) },
                    generator = generator,
                )
                val accepted = root.runtime.appendUserMessage(
                    listOf(ContentItem.InputText("Persist before optional title generation")),
                )
                withTimeout(5.seconds) { job.await() }.join()
                assertEquals(accepted, root.runtime.latestIndex.value)
                assertTrue(root.storage.getSettingsAt(accepted).name.startsWith("Session "))
                root.runtime.appendUserMessage(listOf(ContentItem.InputText("Do not retry")))
                yield()
                assertEquals(1, calls)
            }
        }

        test("persists one generated title through the AgentState including steer submission") {
            val root = initializedTitleSession()
            val capturedRequest = CompletableDeferred<Triple<String, OpenAiModelId, ReasoningEffort>>()
            attachSessionTitleGeneration(
                scope = root.runtime,
                agentState = root.runtime,
                settings = { SessionTitleSettings(enabled = true, model = null, reasoningEffort = ReasoningEffort.Low) },
                generator = SessionTitleGenerator { text, model, reasoningEffort ->
                    capturedRequest.complete(Triple(text, model, reasoningEffort))
                    SessionTitleGenerationResult.Generated("Plan agent title handoff")
                },
            )
            root.runtime.steer(
                listOf(StableUserMessage(listOf(ContentItem.InputText("Plan the agent title handoff.")))),
            )
            assertEquals(
                Triple("Plan the agent title handoff.", DefaultSessionTitleModel, ReasoningEffort.Low),
                withTimeout(5.seconds) { capturedRequest.await() },
            )
            val titleIndex = withTimeout(5.seconds) {
                root.runtime.latestIndex.first { index ->
                    root.storage.getSettingsAt(index).name == "Plan agent title handoff"
                }
            }
            assertEquals("Plan agent title handoff", root.storage.getSettingsAt(titleIndex).name)
        }

        test("an explicit Agent rename wins over an in-flight generated title") {
            val root = initializedTitleSession()
            val generationStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val job = CompletableDeferred<Job>()
            attachSessionTitleGeneration(
                scope = root.runtime,
                agentState = root.runtime,
                settings = {
                    SessionTitleSettings(
                        enabled = true,
                        model = OpenAiModelId("title-model"),
                        reasoningEffort = ReasoningEffort.High,
                    )
                },
                generator = SessionTitleGenerator { _, _, _ ->
                    job.complete(currentCoroutineContext().job)
                    generationStarted.complete(Unit)
                    release.await()
                    SessionTitleGenerationResult.Generated("Late automatic title")
                },
            )
            root.runtime.appendUserMessage(listOf(ContentItem.InputText("Keep this explicit title.")))
            withTimeout(5.seconds) { generationStarted.await() }
            val renamedAt = root.runtime.updateSettings(
                root.storage.getSettingsAt(root.runtime.latestIndex.value).copy(name = "Manual title"),
            )
            release.complete(Unit)
            job.await().join()
            assertEquals("Manual title", root.storage.getSettingsAt(renamedAt).name)
            assertEquals("Manual title", root.storage.getSettingsAt(root.runtime.latestIndex.value).name)
        }

        test("history replacement restores eligibility when no accepted text remains") {
            val root = initializedTitleSession()
            var calls = 0
            val firstAttempt = CompletableDeferred<Unit>()
            val secondAttempt = CompletableDeferred<Unit>()
            attachSessionTitleGeneration(
                scope = root.runtime,
                agentState = root.runtime,
                settings = { SessionTitleSettings(enabled = true) },
                generator = SessionTitleGenerator { _, _, _ ->
                    val callNumber = ++calls
                    if (callNumber == 1) {
                        firstAttempt.complete(Unit)
                        SessionTitleGenerationResult.Rejected("Keep default title for revert")
                    } else {
                        secondAttempt.complete(Unit)
                        SessionTitleGenerationResult.Generated("Replacement title")
                    }
                },
            )
            root.runtime.appendUserMessage(listOf(ContentItem.InputText("Generate a replacement title.")))
            withTimeout(5.seconds) { firstAttempt.await() }
            assertEquals(1, calls)

            root.runtime.modify { storage -> storage.revert(1) }

            root.runtime.appendUserMessage(listOf(ContentItem.InputText("Second try after revert.")))
            withTimeout(5.seconds) { secondAttempt.await() }
            withTimeout(5.seconds) {
                root.runtime.latestIndex.first { index ->
                    root.storage.getSettingsAt(index).name == "Replacement title"
                }
            }
            assertEquals(2, calls)
        }

        test("history replacement remains consumed when accepted text is retained") {
            val root = initializedTitleSession()
            var calls = 0
            val firstAttempt = CompletableDeferred<Unit>()
            attachSessionTitleGeneration(
                scope = root.runtime,
                agentState = root.runtime,
                settings = { SessionTitleSettings(enabled = true) },
                generator = SessionTitleGenerator { _, _, _ ->
                    val callNumber = ++calls
                    if (callNumber == 1) {
                        firstAttempt.complete(Unit)
                        SessionTitleGenerationResult.Rejected("Keep default title while consuming first text")
                    } else {
                        SessionTitleGenerationResult.Generated("Unexpected title")
                    }
                },
            )
            root.runtime.appendUserMessage(listOf(ContentItem.InputText("Keep the existing title attempt.")))
            withTimeout(5.seconds) { firstAttempt.await() }
            root.runtime.appendUserMessage(listOf(ContentItem.InputText("Discard only the second message.")))
            yield()

            root.runtime.modify { storage -> storage.revert(2) }
            root.runtime.appendUserMessage(listOf(ContentItem.InputText("Third message after partial revert.")))
            delay(50)
            assertEquals(1, calls)
        }
    }
}

private suspend fun CoroutineScope.initializedTitleSession(
    name: (Int) -> String = { "Session $it" },
): KodexAgentSession {
    val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
    val index = repository.create()
    val session = repository.open(index)
    session.runtime.modify { storage ->
        storage.initialize(KodexAgentSettings(model = OpenAiModelId("test-model"), name = name(index)))
    }
    return session
}
