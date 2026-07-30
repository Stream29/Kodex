package io.github.stream29.kodex.agentsession.contract

import io.github.stream29.kodex.agentcontext.contract.AgentContextSettings
import io.github.stream29.kodex.hook.contract.KodexHooks
import io.github.stream29.kodex.mcp.contract.McpService
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.openai.modelcatalog.OpenAiModelCatalog
import io.github.stream29.kodex.utils.shellclient.ShellSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the process-wide services shared by composed Agent sessions.
 *
 * Sessions and runtime composition borrow this dependency set and must not
 * close it. The application composition root closes it after every dependent
 * Session has stopped.
 */
public class KodexAgentDependencies(
    public val client: OpenAiClient,
    public val modelCatalog: OpenAiModelCatalog,
    public val contextSettings: StateFlow<AgentContextSettings>,
    public val shellSettings: StateFlow<ShellSettings>,
    public val mcpService: McpService,
    public val hooks: KodexHooks,
) : AutoCloseable {
    /**
     * Stops higher-level consumers before closing the OpenAI client they use.
     *
     * Every close operation is attempted. The first failure remains primary and
     * later failures are attached as suppressed exceptions.
     */
    override fun close() {
        val failures = listOf<() -> Unit>(
            { hooks.coroutineContext[Job]?.cancel() },
            mcpService::close,
            modelCatalog::close,
            client::close,
        )
            .map { close -> runCatching(close) }
            .filter(Result<Unit>::isFailure)
        if (failures.isEmpty()) return

        val primaryFailure = requireNotNull(failures.first().exceptionOrNull())
        failures.drop(1).forEach { failure ->
            primaryFailure.addSuppressed(requireNotNull(failure.exceptionOrNull()))
        }
        throw primaryFailure
    }
}
