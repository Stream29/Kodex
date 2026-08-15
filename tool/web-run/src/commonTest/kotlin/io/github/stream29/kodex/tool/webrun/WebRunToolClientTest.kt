package io.github.stream29.kodex.tool.webrun

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWebSearchResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableWebSearchToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingWebSearchToolEvent
import io.github.stream29.kodex.openai.ClickOperation
import io.github.stream29.kodex.openai.FindOperation
import io.github.stream29.kodex.openai.FinanceAssetType
import io.github.stream29.kodex.openai.FinanceOperation
import io.github.stream29.kodex.openai.OpenAiSubscriptionAuthState
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiResult
import io.github.stream29.kodex.openai.OpenOperation
import io.github.stream29.kodex.openai.SearchCommands
import io.github.stream29.kodex.openai.SearchQuery
import io.github.stream29.kodex.openai.ScreenshotOperation
import io.github.stream29.kodex.openai.SportsFunction
import io.github.stream29.kodex.openai.SportsLeague
import io.github.stream29.kodex.openai.SportsOperation
import io.github.stream29.kodex.openai.SportsToolName
import io.github.stream29.kodex.openai.TimeOperation
import io.github.stream29.kodex.openai.WeatherOperation
import io.github.stream29.kodex.openai.codexclistorage.CodexCliStorage
import io.github.stream29.kodex.openai.codexclistorage.CodexAuthJson
import io.github.stream29.kodex.openai.client.OpenAiClient
import io.github.stream29.kodex.openai.client.test.InMemoryOpenAiAuthStore
import io.github.stream29.kodex.openai.client.OpenAiClientConfig
import io.github.stream29.kodex.utils.osenvironment.environmentVariable
import io.github.stream29.kodex.utils.osenvironment.userHomeDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.serialization.encodeToString
import kotlin.random.Random
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

private const val OpenAiCodexPage: String = "https://openai.com/index/introducing-codex/"
private const val OpenAiCodexSystemCard: String =
    "https://cdn.openai.com/pdf/8df7697b-c1b2-4222-be00-1fd3298f351d/codex_system_card.pdf"

private suspend fun realWebRunClient(): OpenAiClient {
    val storage = CodexCliStorage(testCodexDirectory())
    return OpenAiClient(
        authStore = InMemoryOpenAiAuthStore(storage.readAuthOrNull().toSubscriptionAuthStateOrThrow()),
        config = OpenAiClientConfig(),
    )
}

private fun CodexAuthJson?.toSubscriptionAuthStateOrThrow(): OpenAiSubscriptionAuthState {
    val tokens = this?.tokens ?: error("Codex CLI auth tokens are required.")
    return OpenAiSubscriptionAuthState(
        accessToken = tokens.accessToken,
        accountId = tokens.accountId?.takeIf(String::isNotBlank),
    )
}

private val TestModel: OpenAiModelId = OpenAiModelId("gpt-5.6-sol")

private fun testCodexDirectory(): Path {
    val explicitCodexHome = environmentVariable("CODEX_HOME")?.takeIf(String::isNotBlank)
    if (explicitCodexHome != null) return Path(explicitCodexHome)
    return userHomeDirectory()?.let { home -> Path(home, ".codex") }
        ?: error("CODEX_HOME or a readable user home directory must be set for real web.run tests.")
}

private fun testSessionId(): String =
    "kodex-web-run-test-${Random.nextLong().toString().replace('-', '0')}"

private suspend fun OpenAiClient.webRunClient(): WebRunToolClient =
    WebRunToolClient(
        client = this,
        sessionId = testSessionId(),
        modelProvider = { TestModel },
    )

private suspend fun WebRunToolClient.runOutputOrFail(commands: SearchCommands): String {
    val response = withContext(Dispatchers.Default) { run(commands) }
    return when (response) {
        is OpenAiResult.Success -> response.value.output
        is OpenAiResult.Failure -> fail(
            "OpenAI web search failed: ${response.error.messageText ?: response.error}",
        )
    }
}

private fun String.assertContainsReference(vararg kinds: String) {
    val kindPattern = kinds.joinToString("|")
    assertTrue(
        Regex("""turn\d+(?:$kindPattern)\d+""").containsMatchIn(this),
        "Expected a ${kinds.joinToString()} reference in web.run output: ${take(500)}",
    )
}

private fun String.firstPageReference(): String =
    Regex("""turn\d+(?:view|fetch)\d+""").find(this)?.value
        ?: fail("Expected a page reference in web.run output: ${take(500)}")

private fun String.firstNumberedLinkId(): Long =
    lineSequence()
        .filter { line -> line.startsWith("L") && "cite" in line }
        .mapNotNull { line ->
            Regex("""cite\D+(\d+)""").find(line)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
        }
        .firstOrNull()
        ?: fail("Expected a numbered link in web.run output: ${take(500)}")

val webRunToolClientTest by testSuite {
    testFixture { realWebRunClient() } closeWith { close() } asParameterForEach {
        test(
            "run calls the real web search endpoint",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 120.seconds),
        ) { client ->
            val output = client.webRunClient().runOutputOrFail(
                SearchCommands(
                    searchQuery = listOf(
                        SearchQuery(
                            q = "OpenAI Codex official documentation",
                            domains = listOf("openai.com"),
                        ),
                    ),
                ),
            )

            output.assertContainsReference("search")
        }

        test(
            "run calls the real image search endpoint",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 120.seconds),
        ) { client ->
            val output = client.webRunClient().runOutputOrFail(
                SearchCommands(imageQuery = listOf(SearchQuery("OpenAI logo"))),
            )

            output.assertContainsReference("image")
        }

        test(
            "run calls the real finance endpoint",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 120.seconds),
        ) { client ->
            val output = client.webRunClient().runOutputOrFail(
                SearchCommands(
                    finance = listOf(
                        FinanceOperation(
                            ticker = "MSFT",
                            type = FinanceAssetType.Equity,
                            market = "USA",
                        ),
                    ),
                ),
            )

            output.assertContainsReference("finance")
            assertTrue("MSFT" in output, "Expected the requested ticker in finance output.")
        }

        test(
            "run calls the real weather endpoint",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 120.seconds),
        ) { client ->
            val output = client.webRunClient().runOutputOrFail(
                SearchCommands(
                    weather = listOf(
                        WeatherOperation(
                            location = "San Francisco, CA",
                            duration = 1,
                        ),
                    ),
                ),
            )

            output.assertContainsReference("forecast")
            assertTrue("San Francisco" in output, "Expected the requested location in weather output.")
        }

        test(
            "run calls the real sports endpoint",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 120.seconds),
        ) { client ->
            val output = client.webRunClient().runOutputOrFail(
                SearchCommands(
                    sports = listOf(
                        SportsOperation(
                            tool = SportsToolName.Sports,
                            function = SportsFunction.Standings,
                            league = SportsLeague.Nfl,
                        ),
                    ),
                ),
            )

            output.assertContainsReference("sports")
        }

        test(
            "run calls the real time endpoint",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 120.seconds),
        ) { client ->
            val output = client.webRunClient().runOutputOrFail(
                SearchCommands(time = listOf(TimeOperation("+00:00"))),
            )

            output.assertContainsReference("time")
            assertTrue("UTC+00:00" in output, "Expected the requested UTC offset in time output.")
        }

        test(
            "run opens finds and clicks through a real page",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 120.seconds),
        ) { client ->
            val toolClient = client.webRunClient()
            val openedPage = toolClient.runOutputOrFail(
                SearchCommands(open = listOf(OpenOperation(OpenAiCodexPage))),
            )
            val pageReference = openedPage.firstPageReference()
            val linkId = openedPage.firstNumberedLinkId()

            val foundText = toolClient.runOutputOrFail(
                SearchCommands(
                    find = listOf(
                        FindOperation(
                            refId = pageReference,
                            pattern = "How Codex works",
                        ),
                    ),
                ),
            )
            val clickedPage = toolClient.runOutputOrFail(
                SearchCommands(click = listOf(ClickOperation(pageReference, linkId))),
            )

            assertTrue("How Codex works" in foundText, "Expected the requested text in find output.")
            clickedPage.assertContainsReference("view", "fetch")
        }

        test(
            "run screenshots a real PDF reference",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 120.seconds),
        ) { client ->
            val toolClient = client.webRunClient()
            val openedPdf = toolClient.runOutputOrFail(
                SearchCommands(open = listOf(OpenOperation(OpenAiCodexSystemCard))),
            )
            val pdfReference = openedPdf.firstPageReference()
            val screenshot = toolClient.runOutputOrFail(
                SearchCommands(
                    screenshot = listOf(
                        ScreenshotOperation(
                            refId = pdfReference,
                            pageno = 0,
                        ),
                    ),
                ),
            )

            screenshot.assertContainsReference("view")
        }

        test(
            "tool handler forwards a real web search result",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 120.seconds),
        ) { client ->
            val commands = SearchCommands(searchQuery = listOf(SearchQuery("OpenAI Codex")))
            val tool = WebRunTools.createTool(
                WebRunToolClient(
                    client = client,
                    sessionId = testSessionId(),
                    modelProvider = { testModel() },
                ),
            )
            val completed = withContext(Dispatchers.Default) {
                assertIs<StableWebSearchToolEvent>(
                    tool.handle(
                        PendingWebSearchToolEvent(
                            callId = "call_web_run",
                            commands = commands,
                        ),
                    ),
                )
            }
            val output = assertIs<StableWebSearchResult.Success>(completed.result).response

            val text = output.output
            assertTrue(text.isNotBlank())
            text.assertContainsReference("search")
        }
    }
}
