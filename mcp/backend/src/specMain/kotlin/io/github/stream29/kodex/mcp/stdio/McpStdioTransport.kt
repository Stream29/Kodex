package io.github.stream29.kodex.mcp.stdio

import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.utils.processclient.ProcessClient
import io.modelcontextprotocol.kotlin.sdk.shared.Transport

/**
 * Starts an MCP server over the real direct-process contract.
 *
 * The transport owns the returned raw session; closing it or its delegate
 * requests process termination exactly once. The process client retains its
 * parent ownership. Transport construction failure awaits that exact session's
 * bounded rollback even after caller cancellation, preserving the constructor
 * failure with any cleanup failure suppressed.
 *
 * @throws io.github.stream29.kodex.utils.processclient.ProcessException when
 * the process owner is closed or the platform rejects process creation.
 * @throws kotlinx.coroutines.CancellationException when startup is cancelled.
 */
public expect suspend fun ProcessClient.openMcpStdioTransport(
    configuration: McpServerConfiguration.Stdio,
): Transport
