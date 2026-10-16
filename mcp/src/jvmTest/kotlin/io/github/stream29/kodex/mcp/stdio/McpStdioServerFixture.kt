package io.github.stream29.kodex.mcp.stdio

import java.io.BufferedWriter

object McpStdioServerFixture {
    private val idPattern = Regex("\"id\"\\s*:\\s*(\"(?:\\\\.|[^\"])*\"|-?\\d+)")

    @JvmStatic
    fun main(args: Array<String>) {
        System.`in`.bufferedReader(Charsets.UTF_8).use { input ->
            System.out.bufferedWriter(Charsets.UTF_8).use { output ->
                while (true) {
                    val request = input.readLine() ?: break
                    val id = idPattern.find(request)?.groupValues?.get(1)
                    when {
                        request.contains("\"method\":\"initialize\"") -> respond(
                            output,
                            id,
                            """{"protocolVersion":"2025-03-26","capabilities":{"tools":{}},"serverInfo":{"name":"stdio-fixture","version":"1.0.0"},"instructions":"stdio fixture"}""",
                        )
                        request.contains("\"method\":\"tools/list\"") -> respond(
                            output,
                            id,
                            """{"tools":[{"name":"environment","description":"Reports fixture process state","inputSchema":{"type":"object","properties":{},"additionalProperties":false,"oneOf":[{"required":[]}]},"outputSchema":{"type":"object","properties":{"state":{"type":"string"}},"required":["state"],"additionalProperties":false}}]}""",
                        )
                        request.contains("\"method\":\"tools/call\"") -> {
                            val state = escape(
                                "env=${System.getenv("KODEX_MCP_STDIO_TEST")};cwd=${System.getProperty("user.dir")}",
                            )
                            respond(
                                output,
                                id,
                                """{"content":[{"type":"text","text":"$state"}],"structuredContent":{"state":"$state"}}""",
                            )
                        }
                        id != null -> {
                            output.write(
                                """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"Method not found"}}""" + "\n",
                            )
                            output.flush()
                        }
                    }
                }
            }
        }
    }

    private fun respond(output: BufferedWriter, id: String?, result: String) {
        output.write("""{"jsonrpc":"2.0","id":$id,"result":${result.trim()}}""" + "\n")
        output.flush()
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
