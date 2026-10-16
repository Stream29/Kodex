package io.github.stream29.kodex.cli.sessiontitle

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.contract.KodexAgentSession
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val agentTitleGenerationTest by testSuite {
    testFixture {
        testSuiteCoroutineScope.supervisorChildScope()
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
        test("image-only and blank text leave eligibility for the first accepted nonblank text") {
            val root = initializedTitleSession()
            val generation = AgentTitleGeneration(root.runtime)
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
            for (content in listOf(
                listOf(ContentItem.InputImage("data:image/png;base64,AA==")),
                listOf(ContentItem.InputText(" \n ")),
            )) {
                root.runtime.appendUserMessage(content)
                assertFalse(generation.start(root.runtime, content, true, null, ReasoningEffort.Low, generator))
            }
            val content = listOf(
                ContentItem.InputText(" "),
                ContentItem.InputImage("data:image/png;base64,AA=="),
                ContentItem.InputText("Use the first nonblank text"),
                ContentItem.InputText("Not a combined prompt"),
            )
            root.runtime.appendUserMessage(content)
            assertTrue(generation.start(
                root.runtime, content, true, OpenAiModelId("configured-title-model"), ReasoningEffort.High, generator,
            ))
            assertEquals("Use the first nonblank text", captured.await())
            job.await().join()
            assertEquals("Name accepted user text", root.storage.settings[root.runtime.latestIndex.value].threadName)
            assertFalse(generation.start(root.runtime, content, true, null, ReasoningEffort.Low, generator))
            assertEquals(1, calls)
        }

        test("disabled and nondefault names consume the first accepted text without requesting a title") {
            for (customName in listOf(false, true)) {
                val root = initializedTitleSession { if (customName) "Chosen initial name" else "Session $it" }
                val generation = AgentTitleGeneration(root.runtime)
                val content = listOf(ContentItem.InputText("Accepted first text"))
                val generator = SessionTitleGenerator { _, _, _ -> error("This gate must not call the provider") }
                root.runtime.appendUserMessage(content)
                assertFalse(generation.start(root.runtime, content, customName, null, ReasoningEffort.Low, generator))
                val current = root.storage.settings[root.runtime.latestIndex.value]
                assertTrue(root.runtime.compareAndSetSettings(current, current.copy(threadName = "Session 99")))
                // Re-enabling or restoring a default name cannot undo the consumed gate.
                assertFalse(generation.start(root.runtime, content, true, null, ReasoningEffort.Low, generator))
            }
        }

        test("rejected and throwing title requests leave the accepted message intact and do not retry") {
            for (throws in listOf(false, true)) {
                val root = initializedTitleSession()
                val generation = AgentTitleGeneration(root.runtime)
                val content = listOf(ContentItem.InputText("Persist before optional title generation"))
                val job = CompletableDeferred<Job>()
                var calls = 0
                val generator = SessionTitleGenerator { _, _, _ ->
                    calls++
                    job.complete(currentCoroutineContext().job)
                    if (throws) error("Synthetic optional generation failure")
                    SessionTitleGenerationResult.Rejected("No usable title")
                }
                val accepted = root.runtime.appendUserMessage(content)
                assertTrue(generation.start(root.runtime, content, true, null, ReasoningEffort.Low, generator))
                job.await().join()
                assertEquals(accepted, root.runtime.latestIndex.value)
                assertTrue(root.storage.settings[accepted].threadName.startsWith("Session "))
                assertFalse(generation.start(root.runtime, content, true, null, ReasoningEffort.Low, generator))
                assertEquals(1, calls)
            }
        }

        test("persists one generated title through the AgentState") {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val sessionIndex = repository.create()
            val root = repository.open(sessionIndex)
            root.runtime.modify { storage ->
                storage.initialize(
                    KodexAgentSettings(
                        model = OpenAiModelId("test-model"),
                        threadName = "Session $sessionIndex",
                    ),
                )
            }
            val content = listOf(ContentItem.InputText("Plan the agent title handoff."))
            root.runtime.appendUserMessage(content)
            val capturedRequest = CompletableDeferred<Pair<OpenAiModelId, ReasoningEffort>>()
            val generation = AgentTitleGeneration(root.runtime)

            assertTrue(
                generation.start(
                    agentState = root.runtime,
                    content = content,
                    enabled = true,
                    model = null,
                    reasoningEffort = ReasoningEffort.Low,
                    generator = SessionTitleGenerator { _, model, reasoningEffort ->
                        capturedRequest.complete(model to reasoningEffort)
                        SessionTitleGenerationResult.Generated("Plan agent title handoff")
                    },
                ),
            )

            assertEquals(DefaultSessionTitleModel to ReasoningEffort.Low, capturedRequest.await())
            val titleIndex = withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(5.seconds) {
                    root.runtime.latestIndex.first { index -> index >= 2 }
                }
            }
            assertEquals("Plan agent title handoff", root.storage.settings[titleIndex].threadName)
        }

        test("an explicit Agent rename wins over an in-flight generated title") {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val sessionIndex = repository.create()
            val root = repository.open(sessionIndex)
            root.runtime.modify { storage ->
                storage.initialize(
                    KodexAgentSettings(
                        model = OpenAiModelId("test-model"),
                        threadName = "Session $sessionIndex",
                    ),
                )
            }
            val content = listOf(ContentItem.InputText("Keep this explicit title."))
            root.runtime.appendUserMessage(content)
            val generationStarted = CompletableDeferred<Unit>()
            val generation = AgentTitleGeneration(root.runtime)

            assertTrue(
                generation.start(
                    agentState = root.runtime,
                    content = content,
                    enabled = true,
                    model = OpenAiModelId("title-model"),
                    reasoningEffort = ReasoningEffort.High,
                    generator = SessionTitleGenerator { _, _, _ ->
                        generationStarted.complete(Unit)
                        CompletableDeferred<SessionTitleGenerationResult>().await()
                    },
                ),
            )
            generationStarted.await()

            val renamedAt = generation.renameThread(root.runtime, "Manual title")

            assertEquals("Manual title", root.storage.settings[renamedAt].threadName)
        }

        test("history replacement restores eligibility when no accepted text remains") {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val sessionIndex = repository.create()
            val root = repository.open(sessionIndex)
            root.runtime.modify { storage ->
                storage.initialize(
                    KodexAgentSettings(
                        model = OpenAiModelId("test-model"),
                        threadName = "Session $sessionIndex",
                    ),
                )
            }
            val generation = AgentTitleGeneration(root.runtime)
            val content = listOf(ContentItem.InputText("Generate a replacement title."))
            val generator = SessionTitleGenerator { _, _, _ ->
                SessionTitleGenerationResult.Rejected("test")
            }

            assertFalse(
                generation.start(
                    agentState = root.runtime,
                    content = content,
                    enabled = false,
                    model = null,
                    reasoningEffort = ReasoningEffort.Low,
                    generator = generator,
                ),
            )

            generation.replaceHistory { false }

            assertTrue(
                generation.start(
                    agentState = root.runtime,
                    content = content,
                    enabled = true,
                    model = null,
                    reasoningEffort = ReasoningEffort.Low,
                    generator = generator,
                ),
            )
        }

        test("history replacement remains consumed when accepted text is retained") {
            val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
            val sessionIndex = repository.create()
            val root = repository.open(sessionIndex)
            root.runtime.modify { storage ->
                storage.initialize(
                    KodexAgentSettings(
                        model = OpenAiModelId("test-model"),
                        threadName = "Session $sessionIndex",
                    ),
                )
            }
            val generation = AgentTitleGeneration(root.runtime)
            val content = listOf(ContentItem.InputText("Keep the existing title attempt."))
            val generator = SessionTitleGenerator { _, _, _ ->
                SessionTitleGenerationResult.Rejected("test")
            }
            assertFalse(
                generation.start(
                    agentState = root.runtime,
                    content = content,
                    enabled = false,
                    model = null,
                    reasoningEffort = ReasoningEffort.Low,
                    generator = generator,
                ),
            )

            generation.replaceHistory { true }

            assertFalse(
                generation.start(
                    agentState = root.runtime,
                    content = content,
                    enabled = true,
                    model = null,
                    reasoningEffort = ReasoningEffort.Low,
                    generator = generator,
                ),
            )
        }
    }
}

/** Real Session/runtime with its ordinary CAS implementation; no title-specific state adapter. */
private suspend fun CoroutineScope.initializedTitleSession(
    name: (Int) -> String = { "Session $it" },
): KodexAgentSession {
    val repository = InMemoryKodexSessionRepository(testKodexAgentDependencies())
    val index = repository.create()
    val session = repository.open(index)
    session.runtime.modify { storage ->
        storage.initialize(KodexAgentSettings(model = OpenAiModelId("test-model"), threadName = name(index)))
    }
    return session
}
