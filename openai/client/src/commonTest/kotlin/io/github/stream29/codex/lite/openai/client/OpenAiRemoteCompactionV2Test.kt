package io.github.stream29.codex.lite.openai.client

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import kotlinx.coroutines.flow.flowOf
import kotlin.test.assertEquals
import kotlin.test.assertNull

val openAiRemoteCompactionV2Test by testSuite {
    test("remote compaction v2 succeeds when compaction output arrives without completed") {
        val compaction = ResponseItem.Compaction(encryptedContent = "compact")

        val response = flowOf<ResponsesStreamEvent>(
            ResponsesStreamEvent.OutputItemDone(outputIndex = 0, item = compaction),
        ).collectRemoteCompactionV2Response()

        assertEquals(compaction, response.compactionOutput)
        assertNull(response.completedResponse)
    }

    test("remote compaction v2 retries when stream closes before compaction output") {
        val compaction = ResponseItem.Compaction(encryptedContent = "compact")
        var attempts = 0

        val response = retryOpenAiStreamingTransport(
            OpenAiClientRetryConfig(
                maxRetries = 1,
                baseDelayMillis = 1,
                maxDelayMillis = 1,
                randomizationMillis = 0,
            ),
        ) {
            attempts += 1
            if (attempts == 1) {
                flowOf<ResponsesStreamEvent>().collectRemoteCompactionV2Response()
            } else {
                flowOf<ResponsesStreamEvent>(
                    ResponsesStreamEvent.OutputItemDone(outputIndex = 0, item = compaction),
                ).collectRemoteCompactionV2Response()
            }
        }

        assertEquals(2, attempts)
        assertEquals(compaction, response.compactionOutput)
    }
}
