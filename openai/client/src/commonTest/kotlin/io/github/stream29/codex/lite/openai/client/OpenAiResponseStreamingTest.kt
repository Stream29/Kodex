package io.github.stream29.codex.lite.openai.client

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val StreamingProbePrompt: String =
    "请用中文写一个至少八百字的完整故事，只输出故事正文。"

val openAiResponseStreamingTest by testSuite {
    testFixture {
        OpenAiClient(
            authProvider = codexAuthProvider(),
            config = OpenAiClientConfig(clientVersion = testCodexClientVersion()),
        )
    } asParameterForEach {
        test(
            "response text deltas arrive over time",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 180.seconds),
        ) { client ->
            val elapsedDeltas = mutableListOf<Duration>()
            val started = TimeSource.Monotonic.markNow()

            withContext(Dispatchers.Default) {
                val stream = client.createResponse(
                    ResponsesApiRequest(
                        model = testCodexModel(),
                        input = listOf(
                            ResponseItem.Message(
                                role = MessageRole.User,
                                content = listOf(ContentItem.InputText(StreamingProbePrompt)),
                            ),
                        ),
                        store = false,
                    ),
                )

                stream.collect { event ->
                    if (event is ResponsesStreamEvent.OutputTextDelta) {
                        elapsedDeltas += started.elapsedNow()
                    }
                }
            }

            assertTrue(elapsedDeltas.size > 1, "Expected multiple output text deltas.")
            val firstDeltaAt = elapsedDeltas.first()
            val lastDeltaAt = elapsedDeltas.last()
            val deltaSpan = lastDeltaAt - firstDeltaAt
            assertTrue(
                deltaSpan >= 200.milliseconds,
                "Expected text deltas to remain time-separated; " +
                    "firstDeltaAt=$firstDeltaAt, lastDeltaAt=$lastDeltaAt, " +
                    "deltaSpan=$deltaSpan, deltaCount=${elapsedDeltas.size}.",
            )
        }
    }
}
