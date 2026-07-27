package io.github.stream29.codex.lite.mcp.stdio

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.mcp.contract.McpServerConfiguration
import io.github.stream29.codex.lite.mcp.contract.McpSettings
import io.github.stream29.codex.lite.mcp.impl.McpServiceImpl
import io.github.stream29.codex.lite.openai.ResponseItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
            val result = assertIs<ResponseItem.McpToolCallOutput>(
                tool.handle(
                    ResponseItem.FunctionCall(
                        name = "mcp__stdio_fixture__environment",
                        arguments = "{}",
                        callId = "stdio-call",
                    ),
                ),
            )
            val text = result.output.content.single().jsonObject.getValue("text").jsonPrimitive.content
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
