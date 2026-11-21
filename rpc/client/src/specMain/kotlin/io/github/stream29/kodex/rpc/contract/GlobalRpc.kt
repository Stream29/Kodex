@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.stream29.kodex.rpc.contract

import io.github.stream29.kodex.agentschedule.contract.ScheduleRecord
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionGroupPage
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionGroupSummary
import io.github.stream29.kodex.rpc.models.SessionSpawnRequest
import io.github.stream29.kodex.rpc.models.SessionSpawnResult
import io.github.stream29.kodex.rpc.models.AgentSqlQueryResult
import io.github.stream29.kodex.rpc.models.AgentSqlSchemaResult
import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.agentcontext.contract.ContextPathEvaluation
import io.github.stream29.kodex.mcp.contract.McpCatalogTool
import io.github.stream29.kodex.mcp.contract.McpCodexImportCandidate
import io.github.stream29.kodex.mcp.contract.McpManagedServerState
import io.github.stream29.kodex.openai.CallToolResult
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetOutcome
import io.github.stream29.kodex.rpc.models.BackendSettings
import io.github.stream29.kodex.rpc.models.CliFrontendSettings
import io.github.stream29.kodex.rpc.models.ContextPathQuery
import io.github.stream29.kodex.rpc.models.DirectoryQuery
import io.github.stream29.kodex.rpc.models.DirectoryListResult
import io.github.stream29.kodex.rpc.models.DirectoryValidationResult
import io.github.stream29.kodex.rpc.models.FrontendErrorAdmission
import io.github.stream29.kodex.rpc.models.OAuthLoginEvent
import io.github.stream29.kodex.rpc.models.OAuthTarget
import io.github.stream29.kodex.utils.kotlinxioserialization.PathAsStringSerializer
import kotlinx.coroutines.flow.Flow
import kotlinx.io.files.Path
import kotlinx.rpc.annotations.Rpc
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * Application-wide RPC capabilities owned by the connected backend, not a Session.
 *
 * Settings and model metadata are independent state sources within this service.
 * Metadata getters initialize local state; subscriptions provide subsequent values.
 * Cancelling a subscription does not close the backend or its shared services.
 * Settings contain full MCP credentials; there is no separate sanitized settings projection.
 * The connected backend implements these capabilities; clients borrow service
 * proxies rather than acquiring ownership of its repositories or resources.
 */
@Rpc
public interface GlobalRpc {
    /**
     * Resolves and validates a directory without listing children. Safe operational failures are
     * result values; cancellation/transport failure still propagate. Does not write or retain a
     * picker owner. A success is an observation, not a permanent filesystem permission or lease.
     */
    public suspend fun validateDirectory(query: DirectoryQuery): DirectoryValidationResult

    /**
     * Lists only direct child directories in the backend environment. Independent of validation
     * for Select: a slow/failed listing must not prevent an independent validation request.
     * Supports overlapping calls; cancellation releases only this read, not backend-owned work.
     */
    public suspend fun listDirectories(query: DirectoryQuery): DirectoryListResult

    /**
     * Batch lexical evaluation using the actual backend Context roots and OS syntax.
     * Returns candidate/original keys and built-in matches only, not duplicate merge decisions.
     * Does not scan files, mutate settings or change existing custom-root interpretation.
     */
    public suspend fun evaluateContextPaths(query: ContextPathQuery): ContextPathEvaluation

    /** Backend-managed frontend projection; consumers never read/write its file themselves. */
    public suspend fun getFrontendSettings(): CliFrontendSettings
    public fun getFrontendSettingsFlow(): Flow<CliFrontendSettings>
    /** Full projection CAS; other sections in the shared file are preserved. No implicit renewal. */
    public suspend fun compareAndSetFrontendSettings(expect: CliFrontendSettings, update: CliFrontendSettings): Boolean

    /** Reads the initial editable settings snapshot; later reads and CAS retries use the flow. */
    public suspend fun getSettings(): BackendSettings

    /** Includes the current editable settings when collection begins, followed by updates. */
    public fun getSettingsFlow(): Flow<BackendSettings>

    /**
     * Atomically compares the current backend settings with [expect] using value equality.
     * A mismatch returns false without writing; a match accepts the complete [update].
     * Equal current, expected and updated values succeed without changing state.
     *
     * Comparison and replacement share the backend settings-write boundary and cover the
     * complete [BackendSettings], including MCP credentials. Frontend settings are not part
     * of this operation. Validation and persistence failures are not comparison mismatches.
     *
     * The backend remains responsible for settings validation and persistence.
     * Success does not synchronize the caller's local projection. After false, clients use
     * the latest subscribed value. With no observed progress, cancellable, paced retries
     * are allowed; clients neither require a different value nor poll the initial Get.
     * A transport failure does not establish whether the write occurred.
     */
    public suspend fun compareAndSetSettings(
        expect: BackendSettings,
        update: BackendSettings,
    ): Boolean

    /** Reads the initial catalog snapshot without requiring a provider refresh. */
    public suspend fun getModels(): List<ModelInfo>

    /** Includes the current catalog in provider order, followed by updates; it is not editable. */
    public fun getModelsFlow(): Flow<List<ModelInfo>>

    /** Reads the initial authentication summary for the currently selected settings.authSource. */
    public suspend fun getAuthentication(): SettingsAuthenticationState

    /**
     * Includes the current authentication summary, followed by changes including source changes.
     * This is a read-only projection, not request credentials or a frontend operation's busy state.
     * Loading, refresh and persistence for both sources are backend responsibilities.
     */
    public fun getAuthenticationFlow(): Flow<SettingsAuthenticationState>

    /** Reads the initial usage projection for the currently authenticated account. */
    public suspend fun getAccountUsage(): SettingsAccountUsageState

    /**
     * Includes current account usage, followed by updates from the backend-owned store.
     * Reuses the existing projection without credentials or private reset attempts.
     * Fallback snapshots must belong to the same account; account changes discard old data.
     * This is read-only, non-persistent state, independent of settings and authentication flows.
     * Cancelling collection does not close the shared usage store.
     */
    public fun getAccountUsageFlow(): Flow<SettingsAccountUsageState>

    /**
     * Requests a provider reload for the current account, not a refetch of frontend cached state.
     * The store publishes loading and result states through [getAccountUsageFlow], retaining only
     * account-safe fallback data. Returning does not imply Available or a synchronized frontend
     * projection: provider failures may be represented by Failed, or auth may be unavailable.
     * Does not consume a reset credit, change credentials or write settings.
     */
    public suspend fun refreshAccountUsage(): Unit

    /**
     * Consumes the exact reset credit explicitly selected and confirmed by the user.
     *
     * [creditId] must be non-blank. The backend validates the account and selected credit;
     * it never falls back to automatic selection or substitutes another credit.
     * Private attempts and provider idempotency keys remain backend implementation details.
     *
     * Returns a definitive provider outcome. Failure to refresh usage after obtaining that
     * outcome must not erase it. An exception or lost reply does not prove no credit was consumed.
     * After failure, the frontend requests [refreshAccountUsage] and lets the user decide whether
     * to make a new explicitly confirmed call; neither refresh nor this call is a result-replay
     * protocol. Do not automatically repeat consumption or replace an unavailable credit.
     */
    public suspend fun consumeUsageReset(creditId: String): CodexRateLimitResetOutcome

    /**
     * Prepares a login or re-login for [target]. The backend binds the supported loopback
     * redirect and the target, state and private PKCE verifier before returning the transient
     * authorization URL. The frontend opens or displays that URL; it does not receive callbacks.
     *
     * OpenAI supports both sources. Existing credentials remain until new credentials are
     * successfully obtained and committed to that source; settings changes must not redirect
     * the write, and completion does not implicitly change settings.authSource.
     *
     * MCP requires an existing OAuth server and a redirect URI matching its configured client.
     * The backend discovers metadata and resolves/registers the client without silently changing
     * the configured redirect URI. Preparation may persist client metadata. Only one current
     * login per server is allowed; the prepared server/OAuth identity must remain valid at commit.
     *
     * Attempt ids are unique across both target kinds within the backend's lifetime and are not
     * reused during that lifetime. A common handle does not relax target-specific login limits.
     *
     * Collect once on the original connection. The first event contains the authorization URL;
     * [OAuthLoginEvent.Committed] follows only after credentials are saved. Completion does not
     * promise that an MCP server is connected. The operation can fail with a sanitized error.
     * Cancelling observation releases a pending callback, not accepted exchange or persistence.
     * A lost connection does not establish whether credentials were committed; never recollect
     * automatically.
     */
    public fun startOAuthLogin(target: OAuthTarget): Flow<OAuthLoginEvent>

    /**
     * Admits one frontend error observation to the bounded backend Hook queue. The result reports
     * admission, not execution success. Messages over 8192 UTF-8 bytes fail. Accepted work may
     * outlive the frontend but not backend shutdown; an unknown reply must not be retried.
     */
    public suspend fun reportFrontendError(message: String?): FrontendErrorAdmission

    /**
     * Explicitly cancels the exact attempt for either target, not a target's newer current login.
     * Unknown or finished attempts are no-ops. Does not delete existing credentials, undo MCP
     * preparation writes or roll back committed credentials. Returning does not promise that all
     * asynchronous cleanup has finished.
     */
    public suspend fun cancelOAuthLogin(attemptId: Long): Unit

    /**
     * Removes local credentials belonging to [source], for either Codex or Kodex storage.
     *
     * Does not delete the other source, change settings.authSource, or revoke tokens remotely.
     * Removing absent credentials succeeds. The backend must prevent its in-flight login or
     * refresh results from restoring removed credentials; this does not stop independent Codex
     * processes or immediately invalidate their caches.
     * If [source] is selected, the resulting authentication summary is published by the flow.
     * A transport failure does not establish whether removal occurred.
     */
    public suspend fun removeAuthentication(source: KodexAuthSource): Unit

    /** Reads the initial MCP manager snapshot, including disabled configured servers. */
    public suspend fun getMcpServers(): List<McpManagedServerState>

    /**
     * Includes the current manager snapshot, followed by updated manager snapshots.
     * Reuses the existing combined state and its configuration summaries; editable configurations
     * remain in [BackendSettings.mcpServers]. These two flows are not an atomic joint snapshot.
     * Cancelling collection does not close shared MCP clients or cancel their operations.
     */
    public fun getMcpServersFlow(): Flow<List<McpManagedServerState>>

    /**
     * Reconnects the existing backend-owned client identified by its exact global server name.
     * Missing clients fail rather than implicitly adding or enabling a server.
     * Replaces the connection and refreshes its catalog; failure retains the previous catalog
     * and publishes the failed connection state. Runtime updates are observed through the flow.
     * This is not a configuration editing command and does not replace configuration validation.
     */
    public suspend fun reconnectMcpServer(serverName: String): Unit

    /**
     * Removes the server's initialized OAuth credentials while retaining its configuration.
     * Requires no active login; callers cancelling first must wait for that login to finish.
     * Does not revoke tokens remotely. Runtime reconciliation closes the authenticated connection.
     */
    public suspend fun logoutMcpServer(serverName: String): Unit

    /**
     * Explicitly reads MCP declarations from the backend's Codex config directory.
     *
     * Returns the existing supported configurations and unsupported-item explanations in server
     * name order. Missing configuration or MCP section yields an empty list; read/parse failures
     * are not successful empty results. Does not read Codex OAuth tokens or arbitrary frontend files.
     * Supported values retain configured headers/environment needed for import and must not be
     * logged; OAuth declarations are uninitialized, without imported login credentials.
     *
     * Does not create a backend preview, classify conflicts with Kodex settings or write settings.
     * The frontend owns filtering, preview and merge decisions, then uses [compareAndSetSettings]
     * against its flow-backed settings snapshot. No separate MCP settings update API is needed.
     * This is a fresh explicit external-source read, not a subscription or continuous Codex sync.
     */
    public suspend fun getCodexMcpSettings(): List<McpCodexImportCandidate>

    /**
     * Reads a fresh lightweight persisted Session catalog on each explicit request.
     *
     * This unpaginated query targets a single user with a modest total Session count, where
     * reading the entire matching catalog is acceptable. Supporting larger-scale use requires
     * changing this query to pagination; no cursor, page size or page result is introduced now.
     *
     * The frontend calls when opening the catalog or changing its archived filter and keeps its
     * own loading state and result snapshot. This query has no companion subscription and is not
     * restricted to initializing a flow-backed value. Later changes need not update an open view.
     *
     * When [includeArchived] is false, exclude archived entries before reading their metadata.
     * Preserve catalog order: latest activity descending, then Session index descending.
     * Each entry includes createdAt from exact timestamp zero and updatedAt from the latest
     * persisted timestamp. Catalog menus use these snapshots without separate date queries
     * or Session activation. Dates refresh with the catalog, not on each menu opening.
     * Uninitialized titles and absent timestamps remain null. Failures are not empty success
     * results. Reuse of a stale repository inventory must not defeat an explicit refresh.
     * Each entry's running flag samples the root Agent's unified runningTurn presence, not
     * frontend tabs or cached-owner presence. It includes ordinary execution and manual
     * compaction, not history operations, frontend waits or every auxiliary task's lifetime.
     * Its separate isActive flag samples backend owner residency from the repository, so an
     * idle open Session can be active without running. Neither flag renews or pins that owner.
     *
     * Does not open Agent runtimes, select tabs, unarchive entries or close Session owners.
     * Does not retain a per-frontend catalog or filter in the backend.
     */
    public suspend fun getSessionCatalog(includeArchived: Boolean): List<SessionCatalogEntry>

    /** Emits the current invalidation token, then conflates changes to the group headers. */
    public fun getSessionGroupsNonceFlow(): Flow<Long>

    public suspend fun listSessionGroups(): List<SessionGroupSummary>

    /** Emits the current process-local token for this exact nullable cwd. */
    public fun getSessionGroupNonceFlow(cwd: String?): Flow<Long>

    public suspend fun listSessionsInGroup(cwd: String?, includeArchived: Boolean, limit: Int? = null): SessionGroupPage

    /**
     * Allocates and initializes a persisted Session, returning its index without submitting content.
     *
     * The backend captures its current [BackendSettings.newSession] once at actual creation,
     * combines its four model defaults with [cwd], and validates and initializes the settings.
     * Frontend settings projections are not creation inputs; no configuration synchronization
     * barrier is added. Edits not yet held by the backend need not affect this creation.
     * The title is "Session <index>" using the allocated index. No title or first message is accepted.
     *
     * A creation failure cleans up its newly reserved target. Once creation succeeds, a later
     * submission failure does not delete the Session. A lost reply does not establish that no
     * Session was created, so the frontend must not blindly replay creation.
     * Does not select tabs, consume the frontend composer or establish a keepalive lease.
     */
    public suspend fun createSession(cwd: @Serializable(with = PathAsStringSerializer::class) Path): Int

    /** Creates an independent root with verified source settings and/or explicit overrides. Never submits work. */
    public suspend fun spawnSession(request: SessionSpawnRequest): SessionSpawnResult

    /** Local-credential connections only. A cancelled caller cancels this read, not the Backend. */
    public suspend fun queryAgentSql(sql: String): AgentSqlQueryResult

    /** Local-credential connections only. Does not open the DB or occupy the query slot. */
    public suspend fun getAgentSqlSchema(): AgentSqlSchemaResult

    /**
     * Creates and persists an external one-shot (`cron == null`) or recurring 5-field cron schedule
     * targeting [agentId] (`kodex schedule create`).
     */
    public suspend fun createSchedule(
        agentId: Int,
        triggerAt: Instant,
        cron: String? = null,
        description: String,
    ): ScheduleRecord

    /** Lists active persisted schedules targeting [agentId] (`kodex schedule list`). */
    public suspend fun listSchedules(agentId: Int): List<ScheduleRecord>

    /** Deletes schedule [scheduleId], returning `true` if it existed (`kodex schedule delete`). */
    public suspend fun deleteSchedule(scheduleId: Long): Boolean

    /**
     * Lists published MCP tools across all enabled MCP clients (`kodex tool list`,
     * `kodex mcp status`, and dynamic `kodex <server> <tool>` CLI domains).
     */
    public suspend fun listMcpTools(): List<McpCatalogTool>

    /**
     * Invokes [toolName] on enabled MCP server [serverName] with [arguments] (`kodex <server> <tool>`).
     *
     * Any `type == "image"` content block in the MCP result is decoded and persisted under
     * `<KodexHome>/media/mcp/<serverName>/...` and replaced with saved file path metadata so
     * callers can inspect images on demand via `read_media(path)` without dumping raw base64.
     */
    public suspend fun callMcpTool(
        serverName: String,
        toolName: String,
        arguments: JsonObject = JsonObject(emptyMap()),
    ): CallToolResult

    /** Finds the Session's persisted root AgentId without activating it; a missing Session fails with SessionNotFound. */
    public suspend fun getRootAgentId(sessionIndex: Int): Int

    /**
     * Archives an existing persisted Session without stopping work or closing its shared owner.
     * Repeating the same operation is idempotent; a missing Session fails with SessionNotFound
     * rather than being created.
     * Does not close or select frontend tabs. Catalog snapshots change only when queried again.
     * A transport failure does not establish whether the marker was written.
     */
    public suspend fun archiveSession(sessionIndex: Int): Unit

    /**
     * Idempotently removes an existing Session's archive marker without opening its runtime.
     * A missing Session fails with SessionNotFound. Does not open/select frontend tabs or create
     * a catalog subscription.
     * A transport failure does not establish whether the marker was removed.
     */
    public suspend fun unarchiveSession(sessionIndex: Int): Unit

    /**
     * Forks the latest effective context of the exact initialized [agentId] (root or child).
     *
     * Returns the new Session ID. The new Session has one root Agent, with no copied children
     * or navigable child-creation rows. The source need not be active but cannot be running.
     * A missing source fails with AgentNotFound. The source history and archive state do not change.
     *
     * A lost response may mean a new Session was created; do not blindly replay this command.
     */
    public suspend fun forkAgent(agentId: Int): Int

    /**
     * Forks the effective context before [untilExclusive] of the exact [agentId] and returns
     * the new Session ID. Compactions at or after the boundary do not affect the copy.
     *
     * The source must have an active owner and be idle. [expectedCacheNonce] belongs to that
     * owner's backend storage, not a frontend window counter. The nonce and the admission are
     * checked when capturing the source. Stale targets fail with CacheNonceMismatch instead of being
     * silently rebound; a valid target must be reselected or reconfirmed, not automatically
     * retried with a replacement cache nonce.
     *
     * Preserves initialization, accepts sparse exclusive boundaries within the source storage,
     * and uses the settings and title at that boundary. The same single-root, no-children
     * and source-preservation rules as [forkAgent] apply. A failed target is cleaned up.
     *
     * Accepted work is backend-owned and does not renew TTL. Cancelling the caller's wait is not
     * cancellation of that work. A lost reply may hide successful creation; do not blindly retry.
     */
    public suspend fun forkAgentHistory(
        agentId: Int,
        untilExclusive: Int,
        expectedCacheNonce: Long,
    ): Int

    /**
     * Deletes persisted Session data after shutting down its matching shared backend owner.
     *
     * Returns true when deleted, false when absent; operational failures throw instead of returning
     * false. Absence here does not throw SessionNotFound. This affects every frontend using that
     * Session, not just the caller's local tab.
     * User confirmation and navigation changes remain frontend responsibilities.
     * Does not push a new catalog snapshot; clients query [getSessionCatalog] when needed.
     * A transport failure does not establish whether deletion occurred.
     */
    public suspend fun deleteSession(sessionIndex: Int): Boolean
}
