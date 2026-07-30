package io.github.stream29.codex.lite.mcp.stdio

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentstorage.cleanmodels.stable.StableMcpToolEvent
import io.github.stream29.codex.lite.agentstorage.cleanmodels.unstable.PendingMcpToolEvent
import io.github.stream29.codex.lite.mcp.contract.McpServerConfiguration
import io.github.stream29.codex.lite.mcp.contract.McpSettings
import io.github.stream29.codex.lite.mcp.impl.McpServiceImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

val mcpStdioTransportIoTest by testSuite(
    compartment = { TestCompartment.RealTime },
) {
    test("service executes a real stdio MCP child process") {
        val workingDirectory = Files.createTempDirectory("codex-lite-mcp-stdio-")
        val scope = CoroutineScope(currentCoroutineContext())
        val service = scope.McpServiceImpl(
            settings = MutableStateFlow(
                TestMcpSettings(
                    mapOf(
                        "stdio fixture" to McpServerConfiguration.Stdio(
                            command = javaExecutable(),
                            args = listOf(
                                "-cp",
                                fixtureClasspath(),
                                McpStdioServerFixture::class.java.name,
                            ),
                            environment = mapOf(TestEnvironmentName to TestEnvironmentValue),
                            workingDirectory = Path(workingDirectory.absolutePathString()),
                        ),
                    ),
                ),
            ),
        )
        try {
            val tool = withTimeout(10.seconds) {
                service.tools.first(List<*>::isNotEmpty).single()
            }
            val completed = assertIs<StableMcpToolEvent>(
                tool.handle(
                    PendingMcpToolEvent(
                        callId = "stdio-call",
                        name = "environment",
                        namespace = "mcp__stdio_fixture",
                        arguments = JsonObject(emptyMap()),
                    ),
                ),
            )
            val text = completed.result.content.single().jsonObject.getValue("text").jsonPrimitive.content
            assertEquals(
                "env=$TestEnvironmentValue;cwd=${workingDirectory.toRealPath().absolutePathString()}",
                text,
            )
        } finally {
            service.close()
            service.coroutineContext[Job]?.join()
            workingDirectory.deleteIfExists()
        }
    }
}

private data class TestMcpSettings(
    override val mcpServers: Map<String, McpServerConfiguration>,
) : McpSettings

private fun javaExecutable(): String =
    java.nio.file.Path.of(
        System.getProperty("java.home"),
        "bin",
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "java.exe"
        } else {
            "java"
        },
    ).absolutePathString()

private fun fixtureClasspath(): String =
    java.nio.file.Path.of(
        McpStdioServerFixture::class.java.protectionDomain.codeSource.location.toURI(),
    ).absolutePathString()

private const val TestEnvironmentName: String = "CODEXLITE_MCP_STDIO_TEST"
private const val TestEnvironmentValue: String = "stdio-environment"
