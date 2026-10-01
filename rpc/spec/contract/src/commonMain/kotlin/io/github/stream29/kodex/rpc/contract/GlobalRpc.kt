package io.github.stream29.kodex.rpc.contract

import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogEntry
import io.github.stream29.kodex.app.settings.contract.SettingsAccountUsageState
import io.github.stream29.kodex.app.settings.contract.SettingsAuthenticationState
import io.github.stream29.kodex.cli.settings.KodexAuthSource
import io.github.stream29.kodex.mcp.contract.McpCodexImportCandidate
import io.github.stream29.kodex.mcp.contract.McpManagedServerState
import io.github.stream29.kodex.openai.KodexAgentSettings
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.accountusage.CodexRateLimitResetOutcome
import io.github.stream29.kodex.rpc.models.BackendSettings
import io.github.stream29.kodex.rpc.models.CreatedSuggestedSession
import io.github.stream29.kodex.rpc.models.Notification
import io.github.stream29.kodex.rpc.models.OAuthAuthorization
import io.github.stream29.kodex.rpc.models.OAuthTarget
import io.github.stream29.kodex.tool.multiagent.SuggestedSubagentTask
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

/**
 * Application-wide RPC capabilities owned by the connected backend, not a Session.
 *
 * Settings and model metadata are independent state sources within this service.
 * Metadata getters initialize local state; subscriptions provide subsequent values.
 * Cancelling a subscription does not close the backend or its shared services.
 * Settings contain full MCP credentials; there is no separate sanitized settings projection.
 * This review contract has no client or server implementation.
 */
@Rpc
public interface GlobalRpc {
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

    /**
     * Observes backend-produced notifications for all persisted root Agents.
     *
     * This is a broadcast event flow with replay=0 semantics, not a state subscription:
     * no initial snapshot, prior events, offline backlog or acknowledgement is provided.
     * A Session's inactivity does not terminate this global subscription. Cancelling collection
     * releases only this observer, not backend Agent execution.
     *
     * At one host-interaction stop, publish one notification per present request type, preserving
     * all calls of that type in their original order. Mixed types produce separate notifications,
     * not an atomic batch. Notifications are not proof that a request remains pending.
     *
     * Frontend Hooks consume observations only; their result or failure cannot control the Agent.
     * RPC failures and backend logs remain independent. This declaration implements neither
     * event publication nor a buffering policy and does not promise durable delivery.
     */
    public fun getNotificationFlow(): Flow<Notification>

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
     * Prepares a login or re-login for [target] and the frontend's [redirectUri].
     *
     * The frontend starts its loopback listener before calling, then opens the returned URL and
     * forwards the callback through [completeOAuthLogin]. The backend does not open a browser or
     * listen for callbacks. It validates the supported redirect URI and binds it, the target,
     * OAuth state and its private PKCE verifier to this attempt. The URL is not persistent state.
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
     * Until the frontend submits a callback, there is no independent token exchange to complete.
     * A lost response does not prove that no attempt started; do not blindly replay this command.
     */
    public suspend fun startOAuthLogin(
        target: OAuthTarget,
        redirectUri: String,
    ): OAuthAuthorization

    /**
     * Submits the full callback URL received by the frontend and waits for credential commit.
     *
     * [callbackUrl] is parsed as data, never fetched or followed. Before accepting it, the backend
     * checks the pending attempt, bound redirect URI and OAuth state, rejecting malformed or
     * mismatched responses without consuming a valid pending attempt. A matching OAuth error
     * terminates the attempt as failure, not success. Unknown or consumed attempts fail.
     *
     * A valid code is consumed at most once and exchanged using the bound redirect URI and
     * backend-held verifier. After acceptance, exchange and commit are backend-owned: cancelling
     * this call or disconnecting is not explicit login cancellation. Success means committed;
     * a lost response leaves the outcome unknown and must not trigger blind resubmission.
     * Callback URLs are transient authorization data and must not be logged or persisted.
     * The destination comes from the prepared attempt, never a new target supplied at completion.
     * MCP success means credentials saved, not a connected server; observe [getMcpServersFlow]
     * for connection changes. OpenAI authentication summaries still follow settings.authSource.
     */
    public suspend fun completeOAuthLogin(attemptId: Long, callbackUrl: String): Unit

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

    /**
     * Allocates and initializes a persisted Session, returning its index without submitting content.
     *
     * [initialSettings] comes from the frontend's new-Session draft. Its threadName is not a
     * creation-title input: the backend replaces it with "Session <index>" using the allocated
     * index and validates the effective settings. No title flag or first message is accepted.
     *
     * A creation failure cleans up its newly reserved target. Once creation succeeds, a later
     * submission failure does not delete the Session. A lost reply does not establish that no
     * Session was created, so the frontend must not blindly replay creation.
     * Does not select tabs, consume the frontend composer or establish a keepalive lease.
     */
    public suspend fun createSession(initialSettings: KodexAgentSettings): Int

    /**
     * Creates and starts a suggested-Session batch explicitly confirmed by the frontend.
     *
     * Each task uses [initialSettings] with its own task name as threadName, through the usual
     * creation validation and initialization. Turn and window identities are initialized
     * independently for each child, not copied as shared identities from the input.
     *
     * The backend owns creation, naming, first-message submission and run startup; the frontend
     * does not repeat these steps as per-child RPCs. Returns the complete creation metadata in
     * input task order once startup work is handed off, without waiting for model execution.
     * Each result includes the Session index and original URI/name metadata, so constructing
     * the parent tool result does not require per-child metadata queries.
     *
     * Returning does not prove that a child's first message or model execution succeeded.
     * Navigation, tab selection and completing the parent tool call remain frontend concerns.
     * Ordinary [createSession] retains its separate creation and submission semantics.
     *
     * Creation failures propagate through the ordinary exception channel, without a dedicated
     * known-error branch or partial-success result. The backend makes best-effort attempts to
     * limit side effects; no all-or-nothing rollback is promised. An exception or lost reply does
     * not prove that nothing was created, and must not trigger automatic replay of the batch.
     * Cancelling the caller's wait does not revoke backend work already accepted.
     */
    public suspend fun createSuggestedSessions(
        tasks: List<SuggestedSubagentTask>,
        initialSettings: KodexAgentSettings,
    ): List<CreatedSuggestedSession>

    /**
     * Activates an existing Session and renews its backend retention deadline.
     * Returns after activation is ready, without holding a long-lived keep-alive call.
     *
     * The frontend calls periodically while it needs to keep the Session resident. Missing
     * persisted Sessions fail with SessionNotFound instead of being created; the frontend
     * stops automatic keep-alive/re-subscription of that missing binding. This does not
     * classify storage or permission failures as absence. Does not unarchive, navigate or grant
     * exclusive access. This is the frontend's TTL-renewal command; the backend also renews the same
     * deadline while runningTurn remains occupied, independently of frontend observation.
     * Ordinary reads, writes, subscriptions, pending steer and shell sessions do not renew it.
     * Access requiring a live Session fails with SessionNotActive rather than implicitly
     * activating it. Success is not a permanent lease.
     *
     * Stopping calls does not revoke an already renewed deadline; there is no closeSession RPC.
     * TTL expiry determines deactivation: pending steer, registered shell sessions and unread
     * output do not veto expiry. Running work stays resident through backend turn keepalive,
     * not an additional retention predicate. Shutdown must still preserve write consistency
     * and resource cleanup. Session-bound subscription upstreams end with SessionNotActive;
     * frontends must activate again before re-subscribing. This requires lifecycle adaptation,
     * not automatic SharedFlow completion. TTL and renewal intervals are integration policy,
     * not per-call lease parameters.
     */
    public suspend fun keepSessionAlive(sessionIndex: Int): Unit

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
     * Forks the complete initialized Session and returns the new persisted Session index.
     *
     * Uses the existing not-running and fork-capability checks, fork-title handling and cleanup
     * of a newly reserved target on failure. Does not change the source's archived state or
     * frontend navigation, and does not leave the target open as a frontend Session.
     * History-range fork semantics are not part of this operation.
     * A confirmed missing persisted source fails with SessionNotFound, not an empty new Session.
     *
     * A lost response may mean a new Session was created; do not blindly replay this command.
     */
    public suspend fun forkSession(sessionIndex: Int): Int

    /**
     * Forks history before [untilExclusive] into a new persisted Session and returns its index.
     *
     * Resolves the source root Agent from [sessionIndex]. [expectedCacheNonce] belongs to its
     * backend index timeline and is captured with the frontend target, not a local window
     * counter. Validate the cache nonce and original not-running/fork admission when capturing the
     * source for this operation. Stale targets fail with CacheNonceMismatch instead of being
     * silently rebound; a valid target must be reselected or reconfirmed, not automatically
     * retried with a replacement cache nonce.
     *
     * Preserves initialization, accepts sparse exclusive boundaries within the source storage,
     * and retains the original boundary settings, fork-title handling and failed-target cleanup.
     * Does not modify source history or navigate the frontend. This differs from [forkSession],
     * which copies the complete Session.
     *
     * Accepted work is backend-owned and does not renew TTL. Cancelling the caller's wait is not
     * cancellation of that work. A lost reply may hide successful creation; do not blindly retry.
     */
    public suspend fun forkSessionHistory(
        sessionIndex: Int,
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
