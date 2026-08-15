package io.github.stream29.kodex.cli.auth

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.cli.settings.InMemoryKodexGlobalSettings
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.cli.settings.KodexGlobalSettings
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.OpenAiAuthState
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.Reasoning
import io.github.stream29.kodex.openai.ReasoningEffort
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesApiRequest
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.openai.client.OpenAiClient
import io.github.stream29.kodex.openai.client.OpenAiClientConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

private const val ProbeMarker: String = "KODEX_RESPONSES_PROBE_OK"

val openAiResponsesProbeJvmTest by testSuite(
    compartment = { TestCompartment.RealTime },
) {
    test("current Kodex credentials receive an assistant response before the stream completes") {
        val home = Path(System.getProperty("user.home"))
        val dataDirectory = Path(home, ".kodex")
        val codexHome = Path(home, ".codex")
        val settings = InMemoryKodexGlobalSettings(
            KodexGlobalSettings(
                codexHome = codexHome,
                authSource = KodexAuthSource.Kodex,
            ),
        )
        val loader = CoroutineScope(currentCoroutineContext()).FileSystemKodexAuthStore(
            dataDirectory = dataDirectory,
            globalSettings = settings,
        )
        val auth = try {
            (loader.state.value as? OpenAiAuthState.Authenticated)
                ?.credentials
                ?: error("Kodex credentials could not be loaded: ${loader.state.value}")
        } finally {
            loader.close()
        }
        val client = OpenAiClient(
            authStore = InMemoryKodexAuthStore(auth),
            config = OpenAiClientConfig(),
        )
        try {
            val events = withTimeout(180.seconds) {
                client.createResponse(
                    ResponsesApiRequest(
                        model = OpenAiModelId("gpt-5.6-sol"),
                        input = listOf(
                            ResponseItem.Message(
                                role = MessageRole.User,
                                content = listOf(
                                    ContentItem.InputText(
                                        "Reply with exactly $ProbeMarker and no other text.",
                                    ),
                                ),
                            ),
                        ),
                        reasoning = Reasoning(effort = ReasoningEffort.Max),
                        serviceTier = ServiceTier.Fast,
                    ),
                ).toList()
            }
            val eventTypes = events.map { event -> event.javaClass.simpleName }
            val outputItemTypes = events
                .filterIsInstance<ResponsesStreamEvent.OutputItemDone>()
                .map { event -> event.item.javaClass.simpleName }
            val assistantText = events
                .filterIsInstance<ResponsesStreamEvent.OutputItemDone>()
                .mapNotNull { event -> event.item as? ResponseItem.Message }
                .filter { message -> message.role == MessageRole.Assistant }
                .flatMap { message -> message.content }
                .filterIsInstance<ContentItem.OutputText>()
                .joinToString(separator = "") { content -> content.text }
            val completed = events.filterIsInstance<ResponsesStreamEvent.Completed>().lastOrNull()
            val failed = events.filterIsInstance<ResponsesStreamEvent.Failed>()
            val incomplete = events.filterIsInstance<ResponsesStreamEvent.Incomplete>()

            println(
                "Responses probe: eventTypes=$eventTypes, outputItemTypes=$outputItemTypes, " +
                    "completed=${completed != null}, endTurn=${completed?.response?.endTurn}, " +
                    "assistantTextLength=${assistantText.length}, markerReceived=${ProbeMarker in assistantText}",
            )

            assertFalse(failed.isNotEmpty(), "Responses probe received response.failed: $failed")
            assertFalse(incomplete.isNotEmpty(), "Responses probe received response.incomplete: $incomplete")
            assertNotNull(completed, "Responses probe did not receive response.completed: $eventTypes")
            assertEquals(ProbeMarker, assistantText.trim())
        } finally {
            client.close()
        }
    }
}
