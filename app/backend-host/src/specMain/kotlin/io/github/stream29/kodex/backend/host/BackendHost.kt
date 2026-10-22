package io.github.stream29.kodex.backend.host

import io.github.stream29.kodex.backend.BackendEndpoint
import io.github.stream29.kodex.openai.client.contract.OpenAiAuthStore
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.openai.client.contract.OpenAiLoginClient
import io.github.stream29.kodex.utils.osenvironment.requireUserHomeDirectory
import kotlinx.io.files.Path

/**
 * Owns the instance lock, prepared Home, backend service graph and network host.
 * Client, server-connection and RPC wait scopes never own the backend resources.
 * Factories are explicit in-process validation seams, not production environment flags.
 */
public expect suspend fun runBackendHost(
    home: Path,
    codexHome: Path = Path(requireUserHomeDirectory(), ".codex"),
    agentsHome: Path = Path(requireUserHomeDirectory(), ".agents"),
    createClient: (OpenAiAuthStore) -> OpenAiClient = { io.github.stream29.kodex.openai.client.OpenAiClient(it) },
    createLoginClient: () -> OpenAiLoginClient = { io.github.stream29.kodex.openai.client.OpenAiLoginClient() },
)

/** Bootstrapping has lifecycle rights; an established TUI connection never calls this function. */
public expect suspend fun ensureBackendForTui(home: Path): BackendEndpoint

/**
 * Independent rolling writers for each process, with bounded inactive history.
 * Never deletes live process groups, legacy Kodex logs or names outside this owner's pattern.
 */
public expect suspend fun initializeProcessLogging(home: Path, processName: String)
