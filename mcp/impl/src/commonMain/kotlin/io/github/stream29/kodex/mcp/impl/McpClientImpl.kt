package io.github.stream29.kodex.mcp.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.mcp.contract.McpClient
import io.github.stream29.kodex.mcp.contract.McpClientFailureReason
import io.github.stream29.kodex.mcp.contract.McpClientState
import io.github.stream29.kodex.mcp.contract.McpServerConfiguration
import io.github.stream29.kodex.mcp.contract.McpTool
import io.github.stream29.kodex.utils.ReadWriteMutex
import io.github.stream29.kodex.utils.coroutines.runCatchingCancellable
import io.github.stream29.kodex.utils.logging.global
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.modelcontextprotocol.kotlin.sdk.types.Tool as SdkTool

internal data class McpClientCatalog(
    val instructions: String = "",
    val tools: List<SdkTool> = emptyList(),
)

internal sealed interface McpClientCallResult<out Value> {
    data class Success<Value>(val value: Value) : McpClientCallResult<Value>

    data class Failure(val cause: Throwable) : McpClientCallResult<Nothing>

    data class Unavailable(val state: McpClientState) : McpClientCallResult<Nothing>
}

internal class McpClientImpl(
    private val owner: McpClientOwner,
    catalog: McpClientCatalog,
) : McpClient {
    override val serverName: String = owner.serverName
    override val state: StateFlow<McpClientState> = owner.state

    private val tools: List<McpTool> = catalog.tools
        .distinctBy(SdkTool::name)
        .map { tool ->
            McpToolImpl(
                owner = this,
                serverInstructions = catalog.instructions,
                tool = tool,
            )
        }

    override fun listTools(): List<McpTool> = tools

    override suspend fun reconnect() {
        owner.reconnect()
    }

    internal suspend fun <Value> call(
        block: suspend (Client) -> Value,
    ): McpClientCallResult<Value> = owner.call(block)
}

/** Logical enabled client retained while browser authentication is required. */
internal class McpAuthenticationBlockedClient(
    override val serverName: String,
    private val tools: List<McpTool>,
) : McpClient {
    override val state: StateFlow<McpClientState> =
        MutableStateFlow<McpClientState>(McpClientState.AuthenticationBlocked).asStateFlow()

    override fun listTools(): List<McpTool> = tools

    /** Authentication, rather than transport reconnection, is the required next action. */
    override suspend fun reconnect(): Unit = Unit
}

internal class McpClientOwner(
    private val scope: CoroutineScope,
    val serverName: String,
    initialConfiguration: McpServerConfiguration,
    private val openTransport: suspend (() -> McpServerConfiguration) -> Transport,
    private val publishCatalog: suspend (McpClientOwner, McpClientCatalog) -> Boolean,
    private val release: () -> Unit = {},
) {
    private val connectionLock = ReadWriteMutex()
    private val reconnectMutex = Mutex()
    private val mutableState = MutableStateFlow<McpClientState>(McpClientState.Connecting)
    private val mutableConfiguration = MutableStateFlow(initialConfiguration)
    private var activeClient: Client? = null

    val state: StateFlow<McpClientState> = mutableState.asStateFlow()
    val configuration: McpServerConfiguration
        get() = mutableConfiguration.value

    fun updateConfiguration(configuration: McpServerConfiguration) {
        mutableConfiguration.value = configuration
    }

    fun client(catalog: McpClientCatalog = McpClientCatalog()): McpClientImpl =
        McpClientImpl(owner = this, catalog = catalog)

    suspend fun reconnect() {
        reconnectMutex.withLock reconnect@{
            val attempt = connectionLock.writer.withLock connection@{
                if (mutableState.value == McpClientState.Closed) return@connection null
                mutableState.value = McpClientState.Connecting
                val previous = activeClient
                activeClient = null
                previous?.closeSafely()

                when (val result = openConnection()) {
                    is ConnectionAttempt.Failed -> result
                    is ConnectionAttempt.Succeeded -> {
                        activeClient = result.client
                        result
                    }
                }
            } ?: return@reconnect

            when (attempt) {
                is ConnectionAttempt.Failed -> {
                    logger.warn(attempt.cause) {
                        "Failed to prepare MCP server $serverName: ${attempt.reason}."
                    }
                    mutableState.compareAndSet(
                        expect = McpClientState.Connecting,
                        update = McpClientState.Failed(attempt.reason),
                    )
                }

                is ConnectionAttempt.Succeeded -> withContext(NonCancellable) {
                    if (!publishCatalog(this@McpClientOwner, attempt.catalog)) {
                        close()
                        return@withContext
                    }
                    mutableState.compareAndSet(
                        expect = McpClientState.Connecting,
                        update = McpClientState.Healthy,
                    )
                }
            }
        }
    }

    suspend fun refresh() {
        val result = connectionLock.reader.withLock {
            val client = activeClient
            if (mutableState.value != McpClientState.Healthy || client == null) {
                return@withLock null
            }
            runCatchingCancellable { client.readCatalog() }
                .also { catalog ->
                    catalog.exceptionOrNull()
                        ?.takeIf(Throwable::isConnectionLoss)
                        ?.let {
                            mutableState.compareAndSet(
                                expect = McpClientState.Healthy,
                                update = McpClientState.Failed(McpClientFailureReason.ConnectionLost),
                            )
                        }
                }
        } ?: return

        result.fold(
            onSuccess = { catalog -> publishCatalog(this, catalog) },
            onFailure = { failure ->
                logger.warn(failure) { "Failed to refresh MCP server $serverName." }
            },
        )
    }

    suspend fun <Value> call(
        block: suspend (Client) -> Value,
    ): McpClientCallResult<Value> =
        connectionLock.reader.withLock {
            val currentState = mutableState.value
            val client = activeClient
            if (currentState != McpClientState.Healthy || client == null) {
                return@withLock McpClientCallResult.Unavailable(currentState)
            }

            runCatchingCancellable { block(client) }
                .fold(
                    onSuccess = { value -> McpClientCallResult.Success(value) },
                    onFailure = { failure ->
                        if (failure.isConnectionLoss()) {
                            val failed = McpClientState.Failed(McpClientFailureReason.ConnectionLost)
                            mutableState.compareAndSet(expect = McpClientState.Healthy, update = failed)
                            McpClientCallResult.Unavailable(failed)
                        } else {
                            McpClientCallResult.Failure(failure)
                        }
                    },
                )
        }

    suspend fun close() {
        connectionLock.writer.withLock {
            if (mutableState.value == McpClientState.Closed) return@withLock
            mutableState.value = McpClientState.Closed
            val client = activeClient
            activeClient = null
            try {
                client?.closeSafely()
            } finally {
                release()
            }
        }
    }

    private suspend fun openConnection(): ConnectionAttempt {
        val transport = runCatchingCancellable {
            openTransport { mutableConfiguration.value }
        }
            .getOrElse { failure ->
                return ConnectionAttempt.Failed(McpClientFailureReason.Transport, failure)
            }
        val client = Client(Implementation(name = "kodex", version = "0.2.5"))
        val transportClosed = MutableStateFlow(false)
        transport.onClose {
            transportClosed.value = true
            connectionClosed(client)
        }

        try {
            val initialized = runCatchingCancellable { client.connect(transport) }
            initialized.exceptionOrNull()?.let { failure ->
                client.closeSafely()
                return ConnectionAttempt.Failed(McpClientFailureReason.Initialization, failure)
            }

            val catalog = runCatchingCancellable { client.readCatalog() }
                .getOrElse { failure ->
                    client.closeSafely()
                    return ConnectionAttempt.Failed(McpClientFailureReason.ToolCatalog, failure)
                }
            if (transportClosed.value) {
                client.closeSafely()
                return ConnectionAttempt.Failed(
                    reason = McpClientFailureReason.ConnectionLost,
                    cause = McpException(RPCError.ErrorCode.CONNECTION_CLOSED, "Connection closed"),
                )
            }
            return ConnectionAttempt.Succeeded(client = client, catalog = catalog)
        } catch (cancellation: CancellationException) {
            client.closeSafely()
            throw cancellation
        }
    }

    private fun connectionClosed(client: Client) {
        scope.launch {
            connectionLock.writer.withLock {
                if (activeClient !== client) return@withLock
                activeClient = null
                if (mutableState.value != McpClientState.Closed) {
                    mutableState.value = McpClientState.Failed(McpClientFailureReason.ConnectionLost)
                }
            }
        }
    }

    private suspend fun Client.closeSafely() {
        withContext(NonCancellable) {
            runCatching { close() }
                .onFailure { failure ->
                    logger.warn(failure) { "Failed to close MCP server $serverName." }
                }
        }
    }
}

private sealed interface ConnectionAttempt {
    data class Succeeded(
        val client: Client,
        val catalog: McpClientCatalog,
    ) : ConnectionAttempt

    data class Failed(
        val reason: McpClientFailureReason,
        val cause: Throwable,
    ) : ConnectionAttempt
}

private suspend fun Client.readCatalog(): McpClientCatalog =
    McpClientCatalog(
        instructions = serverInstructions.orEmpty(),
        tools = listEveryTool(),
    )

private suspend fun Client.listEveryTool(): List<SdkTool> {
    if (serverCapabilities?.tools == null) return emptyList()
    return buildList {
        // `null` starts pagination and later marks its final page.
        var cursor: String? = null
        do {
            val page = listTools(ListToolsRequest(cursor?.let(::PaginatedRequestParams)))
            addAll(page.tools)
            cursor = page.nextCursor
        } while (cursor != null)
    }
}

private fun Throwable.isConnectionLoss(): Boolean =
    when (this) {
        is StreamableHttpError -> true
        is McpException ->
            code == RPCError.ErrorCode.CONNECTION_CLOSED ||
                cause?.isConnectionLoss() == true

        else -> cause?.isConnectionLoss() == true
    }

private val logger by lazy {
    KotlinLogging.logger {}.global()
}
