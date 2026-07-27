package io.github.stream29.codex.lite.mcp.stdio;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class McpStdioServerFixture {
  private static final Pattern ID =
      Pattern.compile("\"id\"\\s*:\\s*(\"(?:\\\\.|[^\"])*\"|-?\\d+)");

  public static void main(String[] args) throws Exception {
    try (var input =
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        var output =
            new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
      String request;
      while ((request = input.readLine()) != null) {
        var id = idOf(request);
        if (request.contains("\"method\":\"initialize\"")) {
          respond(
              output,
              id,
              """
              {"protocolVersion":"2025-03-26","capabilities":{"tools":{}},"serverInfo":{"name":"stdio-fixture","version":"1.0.0"},"instructions":"stdio fixture"}
              """);
        } else if (request.contains("\"method\":\"tools/list\"")) {
          respond(
              output,
              id,
              """
              {"tools":[{"name":"environment","description":"Reports fixture process state","inputSchema":{"type":"object","properties":{},"additionalProperties":false,"oneOf":[{"required":[]}]},"outputSchema":{"type":"object","properties":{"state":{"type":"string"}},"required":["state"],"additionalProperties":false}}]}
              """);
        } else if (request.contains("\"method\":\"tools/call\"")) {
          var state =
              "env="
                  + System.getenv("CODEXLITE_MCP_STDIO_TEST")
                  + ";cwd="
                  + System.getProperty("user.dir");
          respond(
              output,
              id,
              "{\"content\":[{\"type\":\"text\",\"text\":\""
                  + escape(state)
                  + "\"}],\"structuredContent\":{\"state\":\""
                  + escape(state)
                  + "\"}}");
        } else if (id != null) {
          output.write(
              "{\"jsonrpc\":\"2.0\",\"id\":"
                  + id
                  + ",\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}\n");
          output.flush();
        }
      }
    }
  }

  private static String idOf(String request) {
    Matcher matcher = ID.matcher(request);
    return matcher.find() ? matcher.group(1) : null;
  }

  private static void respond(BufferedWriter output, String id, String result) throws Exception {
    output.write(
        "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result.strip() + "}\n");
    output.flush();
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
