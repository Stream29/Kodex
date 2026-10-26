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
import io.github.stream29.kodex.tool.imagegeneration.GeneratedImageOutput
import io.github.stream29.kodex.tool.imagegeneration.ImageGenToolArguments
import io.github.stream29.kodex.tool.toolsearch.SearchToolCallParams
import io.github.stream29.kodex.tool.toolsearch.ToolSearchResult
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecOutput
import io.github.stream29.kodex.tool.unifiedexec.WriteStdinArguments
import io.github.stream29.kodex.tool.viewimage.ViewImageDetail
import io.github.stream29.kodex.tool.viewimage.ViewImageToolArguments
import io.github.stream29.kodex.tool.viewimage.ViewImageToolOutput
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
            "exec_command; tty=true; yield_time_ms=0; max_output_tokens=0",
            ExecCommandArguments("pwd", tty = true, yieldTimeMillis = 0, maxOutputTokens = 0)
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
        val event = StableCommandExecutionToolEvent(
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

    test("webBatchPreservesAllOperationsAndQualifiers") {
        val commands = Json.decodeFromString<SearchCommands>(
            """{
              "search_query":[{"q":"alpha","domains":["a.test","b.test"],"recency":0},{"q":"beta"}],
              "image_query":[{"q":"gamma","domains":["img.test"],"recency":3}],
              "open":[{"ref_id":"ref","lineno":0}],
              "click":[{"ref_id":"ref","id":7}],
              "find":[{"ref_id":"ref","pattern":"needle"}],
              "screenshot":[{"ref_id":"ref","pageno":0}],
              "finance":[{"ticker":"ABC","type":"equity","market":"USA"}],
              "weather":[{"location":"Oslo","start":"2026-10-12","duration":0}],
              "sports":[{"tool":"sports","fn":"schedule","league":"nba","team":"GSW","opponent":"NYK",
                "date_from":"2026-10-12","date_to":"2026-10-13","num_games":0,"locale":"en-US"}],
              "time":[{"utc_offset":"+08:00"}],"response_length":"long"
            }""",
        )
        val event = StableWebSearchToolEvent(
            callId = "web",
            commands = commands,
            result = StableWebSearchResult.Success(SearchResponse(output = "web output", encryptedOutput = "SECRET")),
        )
        runMosaicTest {
            val text = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers { ExpandedEvent(event, 250) }
            }
            listOf(
                "Search alpha; domains=a.test, b.test; recency=0", "Search beta",
                "Search images gamma; domains=img.test; recency=3", "Open ref at line 0",
                "Click link 7 in ref", "Find \"needle\" in ref", "Screenshot ref page 0",
                "Finance ABC; type=equity; market=USA", "Weather Oslo; start=2026-10-12; duration=0",
                "Sports nba schedule; tool=sports; team=GSW; opponent=NYK; date_from=2026-10-12; date_to=2026-10-13; num_games=0; locale=en-US",
                "Time +08:00",
            ).forEach { assertTrue(it in text, it) }
            assertTrue("web.run; response_length=long" in text)
            assertTrue("output: web output" in text)
            assertFalse("SECRET" in text)
            assertFalse("Arguments" in text)
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
        val event = StableToolSearchEvent("search", arguments = SearchToolCallParams("calendar", 4),
            result = ToolSearchResult.Success(tools))
        runMosaicTest {
            val text = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers { ExpandedEvent(event) }
            }
            assertTrue(text.startsWith("v Search available tools\ntool_search; query=calendar; limit=4"))
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

    test("searchInputsStayInBodyAndPreserveLongMultilineQueries") {
        val query = "first\n  " + "x".repeat(290) + "\nlast"
        val event = StableToolSearchEvent("search", arguments = SearchToolCallParams(query, 0),
            result = ToolSearchResult.Success(emptyList()))
        runMosaicTest {
            val text = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers { Box(Modifier.width(100)) { event.render() } }
            }
            assertTrue(text.startsWith("> Search available tools"))
            assertFalse("first" in text)
            assertFalse("limit=" in text)
        }
        runMosaicTest {
            val text = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers { ExpandedEvent(event, 20) }
            }
            assertTrue(text.startsWith("v Search available"))
            assertTrue("tool_search;" in text)
            assertEquals(290, text.count { it == 'x' })
            assertTrue("last; limit=0" in text.replace("\n", ""))
            assertFalse("..." in text)
        }
    }

    test("invalidSearchKeepsInputsAndErrorInBody") {
        val event = StableToolSearchEvent("search", arguments = SearchToolCallParams("", 0),
            result = ToolSearchResult.InvalidArguments("query must not be empty"))
        runMosaicTest {
            val text = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers { ExpandedEvent(event) }
            }
            assertTrue(text.startsWith("v Failed to search available tools\n"))
            assertTrue("tool_search; query=; limit=0" in text)
            assertTrue("error: query must not be empty" in text)
        }
    }

    test("utilityRendersOriginalTextWithoutArgumentOrResultGroups") {
        listOf("clock" to "curr_time", null to "get_context_remaining").forEach { (namespace, name) ->
            val event = StableTextToolEvent("utility", name = name, namespace = namespace,
                arguments = JsonObject(emptyMap()), result = "The original returned sentence.", success = true)
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

    test("imageGenerationUsesOnlySavedFilenameAndReadableFields") {
        val arguments = ImageGenToolArguments("full\nprompt", listOf("/input/a.png"), 0)
        val success = StableImageGenerationToolEvent("image", arguments = arguments,
            result = StableImageGenerationResult.Success(GeneratedImageOutput("BASE64SECRET", "use saved file"), "/out/final.png"))
        runMosaicTest {
            val text = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers { ExpandedEvent(success) }
            }
            assertTrue(text.startsWith("v Generate final.png"))
            assertTrue("referenced_image_paths=/input/a.png; num_last_images_to_include=0" in text)
            assertTrue("prompt: full\nprompt" in text)
            assertTrue("saved_path: /out/final.png" in text)
            assertTrue("output_hint: use saved file" in text)
            assertFalse("BASE64SECRET" in text)
            assertFalse("[generated" in text)
        }
        val noPath = success.copy(result = StableImageGenerationResult.Success(GeneratedImageOutput("BASE64SECRET")))
        runMosaicTest {
            val text = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers { ExpandedEvent(noPath) }
            }
            assertTrue(text.startsWith("v Generate image"))
            assertFalse("saved_path" in text)
            assertFalse("BASE64SECRET" in text)
        }
    }

    test("viewImageKeepsFullPathAndOnlyShowsChangedOutputDetail") {
        val event = StableImageViewToolEvent("image", arguments = ViewImageToolArguments(
            "/images/" + "z".repeat(270) + ".png", ViewImageDetail.Original, "private-env"),
            result = StableImageViewResult.Success(ViewImageToolOutput("data:image/png;base64,SECRET", ViewImageDetail.High)))
        runMosaicTest {
            val text = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers { ExpandedEvent(event, 30) }
            }
            assertEquals(270, text.substringBefore("view_image").count { it == 'z' })
            assertTrue("view_image; detail=original" in text)
            assertTrue("output_detail: high" in text)
            assertFalse("private-env" in text)
            assertFalse("SECRET" in text)
            assertFalse("path:" in text)
            val defaultText = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                ExpandedEvent(event.copy(arguments = ViewImageToolArguments("/image.png")))
            }
            }
            assertFalse("output_detail" in defaultText)
            assertFalse("detail=" in defaultText)
        }
    }

    test("imageAndWebFailuresStayFlatAndPreserveTheirRecordedMessage") {
        val events = listOf<StableCleanEvent>(
            StableImageGenerationToolEvent("image", arguments = ImageGenToolArguments("prompt"),
                result = StableImageGenerationResult.Failure("generation failed")),
            StableImageViewToolEvent("view", arguments = ViewImageToolArguments("/missing.png"),
                result = StableImageViewResult.Failure("view failed")),
            StableWebSearchToolEvent("web", commands = SearchCommands(searchQuery = listOf(SearchQuery("query"))),
                result = StableWebSearchResult.Failure("web failed")),
        )
        events.zip(listOf("generation failed", "view failed", "web failed")).forEach { (event, error) ->
            runMosaicTest {
                val text = setContentAndSnapshot {
                    io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers { ExpandedEvent(event) }
                }
                assertTrue("error: $error" in text)
                assertFalse("> Result" in text)
            }
        }
    }

    test("hostedImageShowsOnlyExplicitReferenceFormatsWithoutPlaceholder") {
        val event = StableImageGenerationCall(ResponseItem.ImageGenerationCall(
            status = "completed", revisedPrompt = "revised", result = "https://example.test/a.png"))
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
        val event = StableInvalidToolCall("bad",
            invocation = InvalidToolInvocation.Function(name = "exec_command", arguments = original),
            message = "invalid syntax")
        runMosaicTest {
            val text = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers { ExpandedEvent(event) }
            }
            assertTrue("exec_command\narguments: $original\nerror: invalid syntax" in text)
            assertFalse("> Invocation" in text)
            val external = setContentAndSnapshot {
                io.github.stream29.kodex.app.ui.WithMosaicComponentRenderers {
                ExpandedEvent(event.copy(invocation = InvalidToolInvocation.Function(
                    name = "external", arguments = original)))
            }
            }
            assertTrue("> Invocation" in external)
            assertTrue("> Error" in external)
            assertFalse("BROKEN" in external)
        }
    }

    test("hostedWebUsesAllQueriesAndNeverInventsAnOutput") {
        val event = StableWebSearchCall(ResponseItem.WebSearchCall(
            action = WebSearchAction.Search("first", listOf("second", "third"))))
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
private fun ExpandedEvent(event: StableCleanEvent, width: Int = 100) {
    Box(Modifier.width(width)) {
        event.render(null, HistoryExpansionBinding({ true }, {}), null)
    }
}

private fun commandEvent(arguments: ExecCommandArguments): StableCommandExecutionToolEvent =
    StableCommandExecutionToolEvent("command", action = StableCommandExecutionAction.ExecCommand(arguments),
        result = StableCommandExecutionResult.Output(
            UnifiedExecOutput("chunk-secret", 0.0, 0, 98765, 0, "[output truncated]")))
