package io.github.stream29.kodex.app.webrenderersmoke

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.infix.testBalloon.framework.core.testSuite
import kotlin.io.encoding.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAgentMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableDeveloperMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.app.history.contract.HistoryStreamingItem
import io.github.stream29.kodex.app.history.contract.HistoryStreamingKind
import io.github.stream29.kodex.app.ui.UiTheme
import io.github.stream29.kodex.app.ui.compose.rememberComposeHistoryContentRenderer
import io.github.stream29.kodex.cli.history.HistoryEventContent
import io.github.stream29.kodex.cli.history.HistoryMessageContent
import io.github.stream29.kodex.cli.history.HistoryStreamingContent
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TimeSource

val offscreenAssistantMarkdownTest by testSuite {
    test("only assistant message text is rendered as Markdown") { offscreenTest {
        val source = "# Heading\n\nHello **bold**"
        val assistant = StableAssistantMessage(listOf(
            ContentItem.OutputText(source),
            ContentItem.OutputText("Second *part*"),
            ContentItem.InputImage("data:image/png;base64,FAKE"),
        ))
        val scene = OffscreenScene.create(width = 500, height = 1000) {
            val renderer = rememberComposeHistoryContentRenderer()
            HistoryEventContent(assistant, renderer)
            HistoryEventContent(StableUserMessage(listOf(ContentItem.InputText("User $source"))), renderer)
            HistoryEventContent(StableDeveloperMessage(listOf(ContentItem.InputText("Developer $source"))), renderer)
            HistoryEventContent(StableAgentMessage("A", "B", listOf(AgentMessageInputContent.InputText("Agent $source"))), renderer)
        }
        try {
            scene.settle("assistant Markdown") { scene.hasText("Heading") && scene.hasText("Hello bold") }
            assertTrue(scene.hasText("Second part"))
            assertTrue(scene.hasText("User $source"))
            assertTrue(scene.hasText("Developer $source"))
            assertTrue(scene.hasText("Agent $source"))
            assertTrue(scene.nodes().any {
                it.config.toString().contains("[inline data,") && !it.config.toString().contains("FAKE")
            })
            assertFalse(scene.nodes().any { it.config.toString().contains("FAKE") })
        } finally { scene.close() }
    } }

    test("Markdown headings use compact 18sp-17sp-16sp hierarchy near 16sp body") { offscreenTest {
        val source = buildString {
            appendLine("# MARKDOWN_HEADING — 一级标题 (18sp Bold)")
            appendLine()
            appendLine("**MARKDOWN_BOLD** — 正文段落示例，字号 16sp Normal，与 H1 标题差距温和紧凑。")
            appendLine()
            appendLine("# H1_TITLE")
            appendLine("## H2_TITLE")
            appendLine("### H3_TITLE")
            appendLine("#### H4_TITLE")
            appendLine("##### H5_TITLE")
            appendLine("###### H6_TITLE")
            appendLine()
            append("BODY_PARAGRAPH")
        }
        val scene = OffscreenScene.create(width = 760, height = 560) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val renderer = rememberComposeHistoryContentRenderer()
                HistoryEventContent(
                    StableUserMessage(listOf(ContentItem.InputText("# MARKDOWN_USER **raw**"))),
                    renderer,
                )
                HistoryEventContent(
                    StableAssistantMessage(listOf(ContentItem.OutputText(source))),
                    renderer,
                )
            }
        }
        try {
            scene.settle("compact Markdown headings") {
                scene.hasText("H1_TITLE") && scene.hasText("H6_TITLE") && scene.hasText("BODY_PARAGRAPH")
            }
            fun styleOf(label: String) = mutableListOf<TextLayoutResult>().also { list ->
                val action = assertNotNull(scene.text(label).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)
                assertTrue(action(list))
            }.single().layoutInput.style

            val h1 = styleOf("H1_TITLE")
            assertEquals(18.sp, h1.fontSize)
            assertEquals(26.sp, h1.lineHeight)
            assertEquals(FontWeight.Bold, h1.fontWeight)

            val h2 = styleOf("H2_TITLE")
            assertEquals(17.sp, h2.fontSize)
            assertEquals(24.sp, h2.lineHeight)
            assertEquals(FontWeight.SemiBold, h2.fontWeight)

            listOf("H3_TITLE", "H4_TITLE", "H5_TITLE", "H6_TITLE").forEach { heading ->
                val style = styleOf(heading)
                assertEquals(16.sp, style.fontSize, heading)
                assertEquals(24.sp, style.lineHeight, heading)
                assertEquals(FontWeight.SemiBold, style.fontWeight, heading)
            }

            val body = styleOf("BODY_PARAGRAPH")
            assertEquals(16.sp, body.fontSize)
            assertEquals(24.sp, body.lineHeight)
            assertEquals(FontWeight.Normal, body.fontWeight)
            println("KODEX_MARKDOWN_HEADINGS_PNG ${Base64.encode(scene.frame())}")
        } finally { scene.close() }
    } }

    test("default message slot stays plain outside graphical History") { offscreenTest {
        val source = "# Index **raw**"
        val scene = OffscreenScene.create {
            HistoryMessageContent(StableAssistantMessage(listOf(ContentItem.OutputText(source))))
        }
        try {
            scene.settle { scene.hasText(source) }
            assertFalse(scene.hasText("Index raw"))
        } finally { scene.close() }
    } }

    test("streamed snapshots append and replace without duplicating Markdown") { offscreenTest {
        val events = MutableSharedFlow<ResponsesStreamEvent>(replay = 16)
        events.emit(ResponsesStreamEvent.OutputItemAdded(0,
            ResponseItem.Message(role = MessageRole.Assistant, content = emptyList())))
        val scene = OffscreenScene.create(width = 500, height = 1000) {
            HistoryStreamingContent(HistoryStreamingItem.Output(HistoryStreamingKind.Message, events),
                rememberComposeHistoryContentRenderer())
        }
        try {
            scene.settle { scene.hasText("Assistant responding") }
            withTimeout(5_000) { events.subscriptionCount.first { it == 1 } }
            events.emit(ResponsesStreamEvent.OutputTextDelta("fixture", 0, 0, "# Heading\n\nHello **"))
            events.emit(ResponsesStreamEvent.OutputTextDelta("fixture", 0, 0, "world**"))
            scene.settle("streamed Markdown") { scene.hasText("Heading") && scene.hasText("Hello world") }
            events.emit(ResponsesStreamEvent.OutputTextDone("fixture", 0, 0, "# Replacement\n\n> quoted"))
            events.emit(ResponsesStreamEvent.ContentPartAdded("fixture", 0, 1,
                ContentItem.InputImage("data:image/png;base64,FAKE")))
            events.emit(ResponsesStreamEvent.OutputTextDelta("fixture", 0, 2, "**Second part**"))
            scene.settle("replacement Markdown") {
                scene.hasText("Replacement") && scene.hasText("quoted") && scene.hasText("[image]") && scene.hasText("Second part")
            }
            assertFalse(scene.hasText("Hello world"))
        } finally { scene.close() }
    } }

    test("streamed inter-Agent message remains raw") { offscreenTest {
        val events = MutableSharedFlow<ResponsesStreamEvent>(replay = 4)
        events.emit(ResponsesStreamEvent.OutputItemAdded(0, io.github.stream29.kodex.openai.ResponseItem.AgentMessage(
            author = "A", recipient = "B", content = listOf(AgentMessageInputContent.InputText("# Agent **raw**")),
        )))
        val scene = OffscreenScene.create {
            HistoryStreamingContent(HistoryStreamingItem.Output(HistoryStreamingKind.AgentMessage, events),
                rememberComposeHistoryContentRenderer())
        }
        try {
            scene.settle { scene.hasText("# Agent **raw**") }
            assertFalse(scene.hasText("Agent raw"))
        } finally { scene.close() }
    } }

    test("long synthetic Markdown stream remains interactive and reaches the final snapshot") { offscreenTest {
        val events = MutableSharedFlow<ResponsesStreamEvent>(replay = 256)
        events.emit(ResponsesStreamEvent.OutputItemAdded(0,
            ResponseItem.Message(role = MessageRole.Assistant, content = emptyList())))
        val sent = MutableStateFlow(0)
        var clicked by mutableStateOf(false)
        val scene = OffscreenScene.create(width = 700, height = 1000) {
            Column {
                Button(onClick = { clicked = true }) { Text("MARKDOWN_HEARTBEAT") }
                HistoryStreamingContent(HistoryStreamingItem.Output(HistoryStreamingKind.Message, events),
                    rememberComposeHistoryContentRenderer())
            }
        }
        try {
            scene.settle { scene.hasText("MARKDOWN_HEARTBEAT") }
            withTimeout(5_000) { events.subscriptionCount.first { it == 1 } }
            coroutineScope {
                val elapsed = TimeSource.Monotonic.markNow()
                val producer = launch {
                    repeat(120) { index ->
                        events.emit(ResponsesStreamEvent.OutputTextDelta(
                            "markdown-benchmark", 0, 0, "\n\n**STREAM_PART_${index.toString().padStart(3, '0')}** " +
                                "synthetic long text 中文 ".repeat(5),
                        ))
                        sent.value = index + 1
                        delay(20)
                    }
                }
                withTimeout(5_000) { sent.first { it >= 5 } }
                try {
                    withTimeout(5_000) {
                        while (true) {
                            scene.advance()
                            if (scene.nodes().any { node ->
                                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { "STREAM_PART_004" in it.text }
                                }) break
                            delay(4)
                        }
                    }
                } catch (failure: TimeoutCancellationException) {
                    throw AssertionError("Fifth rendered chunk did not appear; sent=${sent.value}", failure)
                }
                scene.click("MARKDOWN_HEARTBEAT")
                assertTrue(clicked, "Independent input must be processed while the stream is active")
                assertTrue(sent.value < 120, "The interaction must occur before the final chunk")
                withTimeout(15_000) { producer.join() }
                scene.settle("long Markdown final snapshot") {
                    scene.nodes().any { node ->
                        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { "STREAM_PART_119" in it.text }
                    }
                }
                println("Markdown synthetic stream: 120 chunks, interaction before completion, elapsed ${elapsed.elapsedNow()}")
            }
        } finally { scene.close() }
    } }
}
