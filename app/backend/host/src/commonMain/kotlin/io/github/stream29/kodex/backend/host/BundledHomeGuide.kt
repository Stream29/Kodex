@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.github.stream29.kodex.backend.host

import io.github.stream29.kodex.utils.kotlinxiocoroutines.CoroutineFileSystem
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.uuid.Uuid

internal val currentKodexGuideText = """
    ---
    name: kodex
    description: Operate Kodex via the kodex CLI and understand Kodex Home: sessions, child agents, read-only SQL history, terminals, ask-user prompts, schedules, web search, images, MCP tools, and backend/Home boundaries.
    ---

    # Kodex

    Every `kodex` tool subcommand is a thin client against an already-running Backend Daemon (it never auto-starts a daemon). Target a Backend with `--backend-home <DIR>` (defaults to `KODEX_HOME` or `~/.kodex`) or `--backend-url <URL>`. Target an Agent with `-a/--agent <ID>` (defaults to `KODEX_AGENT_ID`; note `kodex agent sql` queries the selected Home and does not filter by `KODEX_AGENT_ID` automatically). Check `kodex <command> --help` before guessing flags.

    ## Sessions & Agents

    - `kodex session (list | spawn | rename | archive | unarchive | delete)`: manage independent root Sessions. `session_id` and `root_agent_id` are distinct integers.
    - `kodex agent spawn`: delegate a subtask as a child of the calling Agent. Use `kodex session spawn` only for an independent Session.
    - `kodex agent (status | submit | resume | stop | complete | fork | compact | clear-pending)`:
      - `status`: current UTC time, Agent state, and remaining context-window budget.
      - `submit` / `resume`: return on admission without blocking for turn completion; `stop` cancels only the active turn; `complete` completes a child or archives a root Session.
      - `fork --agent <ID>`: copy an Agent's effective history into a new independent Session without starting it.

    ## Read-Only History (`kodex agent sql`)

    - `kodex.db` is the sole authoritative SQLite store; no `sessions/` JSON file mirror is written. Never open, query, or edit `kodex.db` or `.locks/` directly behind a running Backend.
    - Run `kodex agent sql --schema` first to discover public relations, columns, indexes, JSON paths, and query examples.
    - Execute one read-only SQL statement via `kodex agent sql --stdin` (limits: 64 KiB SQL, 1000 ms timeout, 1000 rows / 1 MiB JSON, 256 KiB per cell).
    - Paginate `agent_commits` by `("index", type)` since multiple commit types can share one `"index"`. Query narrow columns or targeted `json_extract(payload_json, ...)` first; on `truncated`, `TIMEOUT`, or `VALUE_TOO_LARGE`, narrow the projection or range instead of blindly lowering `LIMIT`.

    ## Terminals, Ask User & Schedules

    - `kodex terminal (list | read | write | kill)`: inspect or control background shell sessions (`read` is non-destructive; `write` supports UTF-8 text, `--stdin`, and `--eof`).
    - `kodex ask (create | list | get | answer)`: non-blocking structured user prompts (`create` returns immediately; wait for answers via `await_event`).
    - `kodex schedule (create | list | delete)`: persisted one-shot (`--delay-seconds` / `--at`) or recurring (`--cron`) schedules.

    ## Search, Images & MCP Discovery

    - `kodex search (-q <query> | --json <JSON> | --stdin)`: web search and page inspection via the Backend.
    - `kodex image -p <prompt> [-i <path>]`: generate or edit a PNG and print its saved path (inspect with `read_media`).
    - `kodex tool list`: print `<command>\t<summary>` for built-in CLI tools and enabled MCP tools (pipe to `grep` to discover tools).
    - `kodex mcp (list | status | reconnect | enable | disable)` and dynamic `kodex <server> <tool> [--json <JSON> | --stdin]` (`--help` prints the tool's `inputSchema`).

    ## Backend & Kodex Home

    - `kodex backend (start | status | stop | reload)`: inspect status with `kodex backend status`; only start, stop, reload, or edit settings when explicitly requested. Do not start a second Backend on the same Home or remove lock/lease files.
    - Home files (`~/.kodex`): `kodex.db` (authoritative SQLite), `settings.yml` (global settings), `auth.yml` (private credentials — never print, copy, or include in diagnostics), `log/`, `generated_images/`, `version.json` (migration checkpoint, not last application version), `AGENTS.md`, and `skills/`. Keep user files and custom skills intact.
""".trimIndent().plus("\n")

private val currentGuide = currentKodexGuideText.encodeToByteArray()

/**
 * Installs or verifies the bundled `skills/kodex/SKILL.md` guide.
 * Returns false if `skills/kodex/SKILL.md` belongs to the user and was preserved.
 */
internal suspend fun ensureBundledHomeGuide(
    home: Path,
    fileSystem: CoroutineFileSystem = SystemCoroutineFileSystem,
): Boolean {
    val resolvedHome = fileSystem.resolve(home)
    val maxKnownGuideBytes = currentGuide.size.toLong()
    var directory = resolvedHome
    for (name in listOf("skills", "kodex")) {
        val child = Path(directory, name)
        val metadata = fileSystem.metadataOrNull(child)
        when {
            metadata == null -> {
                // Also preserve dangling links, which metadata on some hosts treats as missing.
                if (fileSystem.list(directory).any { it.name == name }) return false
                fileSystem.createDirectories(child)
            }
            !metadata.isDirectory || fileSystem.resolve(child) != child -> return false
        }
        directory = child
    }

    val target = Path(directory, "SKILL.md")
    val metadata = fileSystem.metadataOrNull(target)
    if (metadata != null) {
        if (!metadata.isRegularFile || metadata.size > maxKnownGuideBytes ||
            fileSystem.resolve(target) != target
        ) return false
        return fileSystem.readBytes(target, maxKnownGuideBytes + 1).contentEquals(currentGuide)
    }
    if (fileSystem.list(directory).any { it.name == target.name }) return false

    val temporary = Path(directory, ".kodex-guide-${Uuid.random()}.tmp")
    try {
        fileSystem.writeBytes(temporary, currentGuide, mustCreate = true)
        // A user edit while preparing the temp file takes precedence.
        if (fileSystem.list(directory).any { it.name == target.name }) return false
        fileSystem.atomicMove(temporary, target)
        return true
    } finally {
        withContext(NonCancellable) { fileSystem.delete(temporary, mustExist = false) }
    }
}
