package io.github.stream29.kodex.cli.history

import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.MouseEvent
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.ui.Box
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.*
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.tool.readmedia.MediaDetail
import io.github.stream29.kodex.tool.readmedia.ReadMediaToolArguments
import io.github.stream29.kodex.tool.readmedia.ReadMediaToolOutput
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecOutput
import io.github.stream29.kodex.tool.unifiedexec.WriteStdinArguments
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val builtInToolEventViewTest by testSuite {
    test("commandShowsRecordedMetadataWithoutDefaultsOrIdentifiers") {
        val event = commandEvent(
            ExecCommandArguments(
                "printf hello",
                workdir = "/repo",
                yieldTimeMillis = 10_000,
                maxOutputTokens = 42,
            ),
        )
        runMosaicTest {
            val text = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers { ExpandedEvent(event) }
            }
            assertTrue("exec_command; cwd=/repo; yield_time_ms=10000; max_output_tokens=42" in text)
            assertTrue("output(exit_code=0; wall_time=0.0s; original_token_count=0):" in text)
            assertTrue("[output truncated]" in text)
            listOf("chunk-secret", "session_id", "Arguments", "Result", "tty=false").forEach {
                assertFalse(it in text, it)
            }
        }
    }

    test("onlyKnownInputDefaultsAreOmitted") {
        assertEquals(
            "exec_command; yield_time_ms=10000",
            ExecCommandArguments("pwd", yieldTimeMillis = 10_000).expandedCommandContext(),
        )
        assertEquals(
            "write_stdin; yield_time_ms=250",
            WriteStdinArguments(1, yieldTimeMillis = 250).expandedCommandContext(),
        )
        assertEquals(
            "exec_command; yield_time_ms=0; max_output_tokens=0",
            ExecCommandArguments("pwd", yieldTimeMillis = 0, maxOutputTokens = 0)
                .expandedCommandContext(),
        )
    }

    test("commandFullTitleKeepsLongMultilineInputAtNarrowWidths") {
        val command = "first\n  " + "x".repeat(290) + "\nlast"
        runMosaicTest {
            val text = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                    ExpandedEvent(commandEvent(ExecCommandArguments(command, yieldTimeMillis = 10_000)), 20)
                }
            }
            assertTrue(text.startsWith("v Run: first\n  "))
            assertTrue("last\nexec_command" in text)
            assertEquals(290, text.substringBefore("exec_command").count { it == 'x' })
            assertFalse("..." in text)
            assertFalse("Command:" in text)
        }
    }

    test("pollHasNoSyntheticExitCodeAndWhitespaceWriteRemainsInput") {
        val event = StableCommandExecution(
            callId = "stdin",
            action = StableCommandExecutionAction.WriteStdin(
                WriteStdinArguments(sessionId = 98765, chars = " \n", yieldTimeMillis = 0),
            ),
            result = StableCommandExecutionResult.Output(
                UnifiedExecOutput("private-chunk", 0.0, originalTokenCount = 0, output = ""),
            ),
        )
        runMosaicTest {
            val text = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers { ExpandedEvent(event) }
            }
            assertTrue("v Write to terminal" in text)
            assertTrue("write_stdin; yield_time_ms=0" in text)
            assertTrue("chars:" in text)
            assertTrue("output(wall_time=0.0s; original_token_count=0):" in text)
            assertFalse("exit_code" in text)
            assertFalse("98765" in text)
        }
    }

    test("searchDefinitionsHaveIndependentDisclosureAndKeepNamespaceWrapper") {
        val tools = Json.decodeFromString<List<LoadableToolSpec>>(
            """[
              {"type":"namespace","name":"calendar","description":"namespace-description","tools":[
                {"type":"function","name":"create","description":"create-description","strict":true,
                 "defer_loading":true,"parameters":{"type":"object","properties":{}}},
                {"type":"function","name":"delete","description":"delete-description",
                 "parameters":{"type":"object","properties":{}}}
              ]},
              {"type":"function","name":"independent","description":"other-description",
               "parameters":{"type":"object","properties":{}}}
            ]""",
        )
        val arguments = Json.parseToJsonElement("""{"query":"calendar","limit":4}""")
        val event = StableServerToolSearch(
            ResponseItem.ServerToolSearchCall(arguments = arguments),
            ResponseItem.ServerToolSearchOutput(status = "completed", tools = tools),
        )
        runMosaicTest {
            val text = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers { ExpandedEvent(event) }
            }
            assertTrue(text.startsWith("v Search available tools\nserver_tool_search; query=\"calendar\"; limit=4"))
            assertTrue("> calendar.create" in text)
            assertTrue("> calendar.delete" in text)
            assertTrue("> independent" in text)
            assertFalse("namespace-description" in text)
            sendMouseEvent(MouseEvent(0, 2, MouseEvent.Type.Press, MouseEvent.Button.Left))
            awaitSnapshot()
            sendMouseEvent(MouseEvent(0, 2, MouseEvent.Type.Release, MouseEvent.Button.Left))
            val expanded = kotlinx.coroutines.withTimeout(5_000) {
                var snapshot = awaitSnapshot()
                while ("namespace-description" !in snapshot) snapshot = awaitSnapshot()
                snapshot
            }
            assertTrue("\"type\": \"namespace\"" in expanded)
            assertTrue("\"strict\": true" in expanded)
            assertTrue("\"defer_loading\": true" in expanded)
            assertTrue("delete-description" in expanded)
            assertTrue("> independent" in expanded)
            assertFalse("other-description" in expanded)
        }
    }

    test("hostedSearchRetainsArbitraryArgumentsAndHasNoEmptyResultGroup") {
        val arguments = Json.parseToJsonElement("""{"query":"custom","limit":0,"nested":{"flag":false}}""")
        val event = StableServerToolSearch(
            ResponseItem.ServerToolSearchCall(arguments = arguments),
            ResponseItem.ServerToolSearchOutput(status = "completed", tools = emptyList()),
        )
        runMosaicTest {
            val text = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers { ExpandedEvent(event, 120) }
            }
            assertTrue("""query="custom"; limit=0; nested={"flag":false}""" in text)
            assertTrue(text.startsWith("v Search available tools\nserver_tool_search; "))
            assertFalse("Arguments" in text)
            assertFalse("Result" in text)
        }
    }

    test("utilityRendersOriginalTextWithoutArgumentOrResultGroups") {
        listOf("clock" to "curr_time", null to "get_context_remaining").forEach { (namespace, name) ->
            val event = StableTextTool(
                "utility",
                name = name,
                namespace = namespace,
                arguments = JsonObject(emptyMap()),
                result = "The original returned sentence.",
                success = true,
            )
            runMosaicTest {
                val text = setContentAndSnapshot {
                    io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers { ExpandedEvent(event) }
                }
                assertTrue("The original returned sentence." in text)
                assertFalse("Arguments" in text)
                assertFalse("Result" in text)
                assertFalse("{}" in text)
                assertFalse("Tool:" in text)
            }
        }
    }

    test("readMediaKeepsFullPathAndOnlyShowsChangedOutputDetail") {
        val event = StableReadMedia(
            "media",
            arguments = ReadMediaToolArguments(
                "/images/" + "z".repeat(270) + ".png",
                MediaDetail.Original,
            ),
            result = StableReadMediaResult.Success(
                ReadMediaToolOutput("data:image/png;base64,SECRET", MediaDetail.High),
            ),
        )
        runMosaicTest {
            val text = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers { ExpandedEvent(event, 30) }
            }
            assertEquals(270, text.substringBefore("read_media").count { it == 'z' })
            assertTrue("read_media; detail=original" in text)
            assertTrue("output_detail: high" in text)
            assertFalse("SECRET" in text)
            assertFalse("path:" in text)
            val defaultText = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                    ExpandedEvent(event.copy(arguments = ReadMediaToolArguments("/image.png")))
                }
            }
            assertFalse("output_detail" in defaultText)
            assertFalse("detail=" in defaultText)
        }
    }

    test("readMediaFailureStaysFlatAndPreservesItsRecordedMessage") {
        val event = StableReadMedia(
            "media",
            arguments = ReadMediaToolArguments("/missing.png"),
            result = StableReadMediaResult.Failure("read media failed"),
        )
        runMosaicTest {
            val text = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers { ExpandedEvent(event) }
            }
            assertTrue("error: read media failed" in text)
            assertFalse("> Result" in text)
        }
    }

    test("hostedImageShowsOnlyExplicitReferenceFormatsWithoutPlaceholder") {
        val event = StableImageGenerationCall(
            ResponseItem.ImageGenerationCall(
                status = "completed",
                revisedPrompt = "revised",
                result = "https://example.test/a.png",
            ),
        )
        runMosaicTest {
            val text = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers { ExpandedEvent(event) }
            }
            assertTrue("v Generate image\nhosted_image_generation" in text)
            assertTrue("revised_prompt: revised" in text)
            assertTrue("result: https://example.test/a.png" in text)
            assertFalse("[generated" in text)
            val encoded = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                    ExpandedEvent(event.copy(item = event.item.copy(result = "ENCODED_PAYLOAD")))
                }
            }
            assertFalse("ENCODED_PAYLOAD" in encoded)
            assertFalse("result:" in encoded)
        }
    }

    test("invalidInternalInputIsNotReparsedButExternalFallbackIsPreserved") {
        val original = "{\"cmd\":\nBROKEN"
        val event = StableInvalidToolCall(
            "bad",
            invocation = InvalidToolInvocation.Function(name = "exec_command", arguments = original),
            message = "invalid syntax",
        )
        runMosaicTest {
            val text = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers { ExpandedEvent(event) }
            }
            assertTrue("exec_command\narguments: $original\nerror: invalid syntax" in text)
            assertFalse("> Invocation" in text)
            val external = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                    ExpandedEvent(
                        event.copy(
                            invocation = InvalidToolInvocation.Function(
                                name = "external",
                                arguments = original,
                            ),
                        ),
                    )
                }
            }
            assertTrue("> Invocation" in external)
            assertTrue("> Error" in external)
            assertFalse("BROKEN" in external)
        }
    }

    test("hostedWebUsesAllQueriesAndNeverInventsAnOutput") {
        val event = StableWebSearchCall(
            ResponseItem.WebSearchCall(
                action = WebSearchAction.Search("first", listOf("second", "third")),
            ),
        )
        runMosaicTest {
            val text = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers { ExpandedEvent(event) }
            }
            assertTrue("v Search first\nSearch second\nSearch third\nhosted_web_search" in text)
            assertFalse("output" in text)
            assertFalse("Arguments" in text)
        }
    }
}

@Composable
private fun ExpandedEvent(event: AgentCommit, width: Int = 100) {
    Box(Modifier.width(width)) {
        event.render(null, HistoryExpansionBinding({ true }, {}), null)
    }
}

private fun commandEvent(arguments: ExecCommandArguments): StableCommandExecution =
    StableCommandExecution(
        "command",
        action = StableCommandExecutionAction.ExecCommand(arguments),
        result = StableCommandExecutionResult.Output(
            UnifiedExecOutput("chunk-secret", 0.0, 0, 98765, 0, "[output truncated]"),
        ),
    )
