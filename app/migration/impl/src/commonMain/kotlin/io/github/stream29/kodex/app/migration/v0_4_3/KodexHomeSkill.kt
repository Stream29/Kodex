package io.github.stream29.kodex.app.migration.v0_4_3

/**
 * Product-managed instructions installed into each Kodex Home by migration 0.4.3.
 *
 * Keep this value version-frozen after release. Later changes require a new migration.
 */
internal val KodexHomeSkill: String = """
    ---
    name: kodex-home
    description: Read Kodex Home and Session storage; change global settings, offline Session settings, and archive sessions through the filesystem.
    ---

    # Kodex Home

    ## Paths

    - `<current_session>.storage_uri` locates this Session; `name` identifies it.

    - Default Home: `~/.kodex`.

    - Home owns `settings.yml`, `auth.yml`, `sessions/`, `AGENTS.md`, `skills/`, `version.json` and `.locks/home/`.
      Process logs and images use `~/.kodex/log/` and `~/.kodex/generated_images/`; archive markers live inside Session directories.

    ## `sessions/`

    `sessions/<non-negative decimal index>/` contains six sparse timelines sharing
    one integer state-index space. `<n>.json` records are authoritative;
    `latest.json` is a reconstructible integer tail pointer.
    Session tail = maximum record index across **all six** timelines.

    | Timeline | Payload |
    | --- | --- |
    | `index` | `compaction_point`, `user_message`, `assistant_message`, `developer_message`, `agent_message`, `plan_update`, `request_user_input_tool_event`, `suggest_subagent_task_tool_event`. |
    | `work` | `reasoning`, `context_compaction`, `command_execution_tool_event`, `patch_tool_event`, `image_view_tool_event`, `image_generation_tool_event`, `web_search_tool_event`, `tool_search_event`, `mcp_tool_event`, `json_tool_event`, `text_tool_event`, `custom_tool_event`, `invalid_tool_call`, `server_tool_search`, `hosted_web_search`, `hosted_image_generation`. |
    | `settings` | Complete settings snapshot. |
    | `timestamp` | ISO-8601 instant string; latest value is Session last activity. |
    | `token-count` | Token-count snapshot. |
    | `unstable` | Array of pending tool calls. |

    - State at `n`: greatest change point `<= n` per timeline.

    - Full stable history: merge actual `index` and `work` records numerically.

    ## `auth.yml`

    - Shape: `{auth_mode: chatgpt, tokens: {id_token: string, access_token: string, refresh_token: string, account_id?: string}, last_refresh: ISO-8601 instant}`.

    ## `settings.yml`

    | Field | Shape |
    | --- | --- |
    | `auth_source` | `codex/kodex` |
    | `shell` | Executable path string; recognized basenames: `sh`, `bash`, `zsh`, `powershell`, `pwsh`, `cmd`. |
    | `new_line_key` | `shift_enter` (Enter submits) or `enter` (Ctrl+Enter submits). |
    | `context_sources` | Optional booleans `agents_home`, `kodex_home`, `codex_home`, `git_root`, `working_directory`; optional `custom_sources: {path: string, enabled?: bool}[]`. |
    | `new_session` | Optional `model: string`, `reasoning_effort: string`, `service_tier: default/priority/fast/flex`, `request_user_input_mode: ask_user/no_question`; only seeds new Sessions. |
    | `session_title` | Optional `enabled: bool`, `model: string`, `reasoning_effort: string`. |
    | `sidebars` | Optional `left`, `right`: `none/terminal_sessions/history_index`; `left_width`, `right_width`: integers ≥ 4. |
    | `mcp_servers` | Map of server name → MCP configuration. |
    | `hooks` | Map of hook name → `{type, command: string}`. Types: `pre_tool_use`, `post_tool_use`, `user_prompt_submit`, `stop`, `pre_compact`, `post_compact`, `unhandled_error`. |

    ## Locks

    - `.locks/home/<pid>.read.lock`: shared application-lifetime lease

    - `.locks/home/<pid>.write.lock`: exclusive initialization/migration lease

    - `<session>/lock.json`: exclusive Session lease

    - All heartbeat files contain `{pid: integer, acquiredAt: ISO-8601 instant, expiresAt: ISO-8601 instant}`.

    ## `archive.mark`

    - Create empty `<session>/archive.mark` to archive; remove it to unarchive.
""".trimIndent() + "\n"
