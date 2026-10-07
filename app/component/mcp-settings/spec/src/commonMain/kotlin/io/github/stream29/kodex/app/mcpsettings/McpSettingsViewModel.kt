package io.github.stream29.kodex.app.mcpsettings

import io.github.stream29.kodex.app.settings.contract.McpServerSettingsState
import io.github.stream29.kodex.mcp.contract.DefaultMcpOAuthRedirectUri
import io.github.stream29.kodex.mcp.contract.McpImportDecision
import io.github.stream29.kodex.mcp.contract.McpImportPreview
import io.github.stream29.kodex.mcp.contract.McpServerDraft
import io.github.stream29.kodex.mcp.contract.McpTransportKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Synchronous application queue admission, never a persistence/connection/authentication receipt.
 * Accepted copies the bound baseline and payload to the shared application queue; their lifetime
 * is independent of component/handle close. Asynchronous CAS conflicts/unknown failures report
 * via operationFailure. Rejected retains the editor/import and displays a local safe message.
 */
public sealed interface McpWriteAdmission {
    public data object Accepted : McpWriteAdmission
    public data class Rejected(public val message: String) : McpWriteAdmission
}

/**
 * Exact editor-opening baseline capability. Raw configuration and credentials stay private.
 * initialDraft contains Keep markers, NEVER persisted secret values; null draft denotes Add.
 * Save renames from originalName with the existing field-conflict/CAS and OAuth identity rules.
 * It cannot replace its baseline with the latest same-name configuration.
 */
public interface McpEditHandle {
    public val originalName: String?
    public val initialDraft: McpServerDraft?
    /** @throws Exception Unexpected admission failure, reported once by the component. */
    public fun save(draft: McpServerDraft): McpWriteAdmission
    /** Idempotent/nonthrowing; does not invalidate an admitted write. */
    public fun release(): Unit
}

/** Captures one name's raw baseline privately for delete/enable; no credentials are exposed. */
public interface McpServerHandle {
    public val serverName: String
    /** @throws Exception Unexpected admission failure. */
    public fun delete(): McpWriteAdmission
    /** @throws Exception Unexpected admission failure. */
    public fun setEnabled(enabled: Boolean): McpWriteAdmission
    /** Idempotent/nonthrowing; already admitted writes retain their own captured values. */
    public fun release(): Unit
}

/**
 * One exact Codex read/baseline capability, with sanitized presentation only. Preview ID is stable
 * across filters. apply must COPY selected candidates/baseline into the application queue BEFORE
 * returning Accepted, so release, hide, replacement or owner cancellation cannot expire them.
 * Value-only Replace does not force invalidation/reconnect when CAS values are equal.
 */
public interface McpImportHandle {
    public val preview: McpImportPreview
    /** Pure, nonthrowing sanitized projection for this exact preview ID; performs no new read. */
    public fun filter(filter: String): McpImportPreview
    /** @throws Exception Unexpected admission failure. */
    public fun apply(decisions: Map<String, McpImportDecision>): McpWriteAdmission
    /** Idempotent/nonthrowing cleanup of UNACCEPTED preview resources only. */
    public fun release(): Unit
}

/**
 * Adapter-owned OAuth attempt, bound to an exact name and exact RPC attempt ID. Listener,
 * redirect, state/PKCE, callback, ten-minute wait and bounded cancellation cleanup remain adapter
 * responsibilities. cancel is idempotent/nonthrowing and NEVER cancels a newer name-bound attempt.
 */
public interface McpSettingsLogin {
    public val serverName: String
    public val authorizationUrl: String
    /**
     * Complete only after existing protocol completion; do not infer backend Healthy.
     * @throws kotlinx.coroutines.CancellationException Interaction wait was cancelled.
     * @throws Exception Existing protocol failed; raw detail is not rendered.
     */
    public suspend fun awaitCompletion(): Unit
    public fun cancel(): Unit
}

/**
 * Typed frontend ports, without GlobalRpc/store/BackendSettings/raw credentials. Sanitized server
 * source is not configuration authority. Commands are dispatcher-confined to the component owner.
 * Reporting/acknowledgement are nonthrowing and application-local; cancellation is never reported.
 */
public interface McpSettingsDependencies {
    public val servers: StateFlow<List<McpServerSettingsState>>
    public val operationFailure: StateFlow<Boolean>
    /**
     * Capture Add (null name) or exact editor target. Missing target returns null.
     * @throws Exception Unexpected capture failure.
     */
    public fun captureEditor(serverName: String?): McpEditHandle?
    /** @throws Exception Unexpected capture failure; missing target returns null. */
    public fun captureServer(serverName: String): McpServerHandle?
    /**
     * Explicit user read only. Cancellation before returning MUST release private preview state;
     * the component releases a returned late handle instead of reopening a replaced dialog.
     * @throws kotlinx.coroutines.CancellationException Read was cancelled.
     * @throws Exception Read/parse failed; component displays a generic retry message.
     */
    public suspend fun readImport(): McpImportHandle
    /**
     * Start existing protocol for exact name. Null means absent/non-OAuth target. Cancellation
     * before return MUST clean listener/known attempt. No browser is opened inside the adapter.
     * @throws kotlinx.coroutines.CancellationException Preparation was cancelled.
     * @throws Exception Existing protocol preparation failed.
     */
    public suspend fun startLogin(serverName: String, interactionScope: CoroutineScope): McpSettingsLogin?
    /** Exact existing server only; no implicit create/enable or shared-client ownership.
     * @throws kotlinx.coroutines.CancellationException Command wait was cancelled.
     * @throws Exception Existing backend command failed.
     */
    public suspend fun reconnect(serverName: String): Unit
    /** Existing logout semantics, not remote revoke or cancellation of a different attempt.
     * @throws kotlinx.coroutines.CancellationException Command wait was cancelled.
     * @throws Exception Existing backend command failed.
     */
    public suspend fun logout(serverName: String): Unit
    public fun reportFailure(failure: Throwable): Unit
    public fun dismissFailure(): Unit
}

/** Identity of one dialog; late callbacks are ignored and never target a replacement dialog. */
public class McpDialogToken

/**
 * VM business draft, including temporarily invalid text and transport-specific inactive fields.
 * <keep> in secret entries/client-secret represents Keep; removing an entry/blank client-secret
 * represents Remove; other entered text represents Replace. No stored secret is copied here.
 * Renderer only buffers cursor/undo/widget input and synchronizes every text edit into this value.
 */
public data class McpEditorDraft(
    public val name: String = "",
    public val enabled: Boolean = true,
    public val transport: McpTransportKind = McpTransportKind.StreamableHttp,
    public val httpUrl: String = "",
    public val command: String = "",
    public val arguments: String = "",
    public val headers: String = "",
    public val environment: String = "",
    public val workingDirectory: String = ".",
    public val oauthEnabled: Boolean = false,
    public val oauthClientId: String = "",
    public val oauthClientSecret: String = "",
    public val oauthRedirect: String = DefaultMcpOAuthRedirectUri,
    public val oauthAuthorizationEndpoint: String = "",
    public val oauthTokenEndpoint: String = "",
    public val oauthResource: String = "",
    public val oauthScopes: String = "",
)

/**
 * Renderer: Hidden draws nothing; Details shows latest exact-name sanitized row and runtime
 * actions (login/cancel/logout by authentication, reconnect only Failed); Editing shows VM text,
 * transport dropdown, OAuth checkbox and error; Deleting uses captured row and cancel-first focus;
 * ImportLoading shows loading; ImportFailed shows generic error/Retry; ImportPreview shows filter,
 * selectable supported rows, VM decisions, Select all/Clear and selected count. Unsupported is
 * never selectable. New defaults Import, Conflict defaults Replace. None implies persisted success.
 */
public sealed interface McpSettingsDialog {
    public data object Hidden : McpSettingsDialog
    public data class Details(
        public val token: McpDialogToken,
        public val server: McpServerSettingsState,
    ) : McpSettingsDialog
    public data class Editing(
        public val token: McpDialogToken,
        public val originalName: String?,
        public val draft: McpEditorDraft,
        public val error: String? = null,
    ) : McpSettingsDialog
    public data class Deleting(
        public val token: McpDialogToken,
        public val server: McpServerSettingsState,
        public val error: String? = null,
    ) : McpSettingsDialog
    public data class ImportLoading(public val token: McpDialogToken) : McpSettingsDialog
    public data class ImportFailed(public val token: McpDialogToken) : McpSettingsDialog
    public data class ImportPreview(
        public val token: McpDialogToken,
        public val preview: McpImportPreview,
        /** All preview decisions, including hidden filtered rows, remain VM-owned. */
        public val decisions: Map<String, McpImportDecision>,
        public val error: String? = null,
    ) : McpSettingsDialog
}

/** Ordered runtime rows / None configured, Add and explicit Import. Failure is generic/dismissible.
 * Closed freezes rows, hides dialog and disables commands; hosts stop rendering it.
 */
public data class McpSettingsState(
    public val servers: List<McpServerSettingsState>,
    public val dialog: McpSettingsDialog = McpSettingsDialog.Hidden,
    public val operationFailure: Boolean = false,
    public val closed: Boolean = false,
)

/**
 * Component-local, single-consumer effect. A Settings-lifetime handler (not just the MCP page)
 * opens the URL once. The handler is retained with the exact Settings owner across a temporary
 * Login popup and return; removing a renderer composition alone does not close the operation.
 * Opening failure invokes cancel EXACTLY on this effect's captured operation;
 * a late failure cannot cancel a newer login, even with the same serverName. URL is ephemeral,
 * never logged, persisted or broadcast globally. Opening is not protocol completion.
 */
public sealed interface McpSettingsEffect {
    public class OpenAuthorizationUrl(
        public val serverName: String,
        public val url: String,
        public val cancel: () -> Unit,
    ) : McpSettingsEffect
}

/**
 * Complete MCP interaction owner. Commands run on owner dispatcher and reject post-close/late
 * tokens. Unknown port failures report once via application state, never raw remote text.
 * Synchronous cancellation propagates; asynchronous cancellation terminates work without failure.
 * Hide clears drafts/import only, NOT OAuth. Close/owner termination stops child observations and
 * short-lived login/read/command waits, but NEVER admitted application writes or shared clients.
 * No automatic read/retry/login/reconnect is performed.
 */
public interface McpSettingsViewModel : AutoCloseable {
    public val state: StateFlow<McpSettingsState>
    public val effects: Flow<McpSettingsEffect>
    /** Capture Add immediately, releasing only a previous unaccepted editor/import.
     * @throws kotlinx.coroutines.CancellationException Dependency capture was cancelled.
     */
    public fun add(): Unit
    /** Latest sanitized exact-name target; missing names are ignored, never matched by position. */
    public fun details(serverName: String): Unit
    /** Capture this Details target before opening its editor.
     * @throws kotlinx.coroutines.CancellationException Dependency capture was cancelled.
     */
    public fun edit(token: McpDialogToken): Unit
    /** Capture exact configuration baseline when requesting delete, not at confirmation.
     * @throws kotlinx.coroutines.CancellationException Dependency capture was cancelled.
     */
    public fun requestDelete(token: McpDialogToken): Unit
    /**
     * Applies [update] synchronously to this token's latest VM-owned draft and clears validation.
     * A late/noneditor token is ignored without invoking [update]. The pure, non-reentrant
     * update runs once and must not capture a prior whole draft, perform I/O or call commands.
     * Update only the intended fields, retaining inactive transport/OAuth fields and other edits.
     *
     * @throws Exception when [update] fails; the draft and validation remain unchanged.
     */
    public fun updateDraft(token: McpDialogToken, update: (McpEditorDraft) -> McpEditorDraft): Unit
    /** Validate baseline parser/name trimming; invalid/rejected admission retains draft.
     * Accepted hides once, without claiming persistence.
     * @throws kotlinx.coroutines.CancellationException Dependency admission was cancelled.
     */
    public fun save(token: McpDialogToken): Unit
    /** Delete captured configuration once.
     * @throws kotlinx.coroutines.CancellationException Dependency admission was cancelled.
     */
    public fun confirmDelete(token: McpDialogToken): Unit
    /** Capture exact current configuration at click, admit into shared queue.
     * @throws kotlinx.coroutines.CancellationException Dependency admission was cancelled.
     */
    public fun setEnabled(token: McpDialogToken, enabled: Boolean): Unit
    /** Existing exact-name backend command; runtime source alone determines resulting status. */
    public fun reconnect(token: McpDialogToken): Unit
    public fun logout(token: McpDialogToken): Unit
    /** One component-owned preparation/wait per exact name; duplicates ignored until cleanup. */
    public fun login(token: McpDialogToken): Unit
    /** Cancel this component's current interaction for the exact Details target only. */
    public fun cancelLogin(token: McpDialogToken): Unit
    /** Explicit read replaces/releases an unaccepted previous preview; late completion ignored. */
    public fun importCodex(): Unit
    /** Retry only a matching failed read, making a new token/preview. No automatic retry. */
    public fun retryImport(token: McpDialogToken): Unit
    /** Filter captured preview without a new RPC; retain decisions for hidden rows. */
    public fun filterImport(token: McpDialogToken, filter: String): Unit
    /** Toggle supported row Skip vs Import/New or Replace/Conflict; unsupported is ignored. */
    public fun toggleImport(token: McpDialogToken, serverName: String): Unit
    /** Select all supported captured rows, including filtered-out rows, or clear all decisions. */
    public fun selectAllImports(token: McpDialogToken): Unit
    /** Clear all decisions including filtered-out rows; applying an empty selection is a no-op. */
    public fun clearImports(token: McpDialogToken): Unit
    /** Admit current preview's decisions once; no selection is a no-op.
     * @throws kotlinx.coroutines.CancellationException Dependency admission was cancelled.
     */
    public fun applyImport(token: McpDialogToken): Unit
    /** Discard only a matching active dialog; release/cancel unaccepted editor/read/preview only. */
    public fun dismiss(token: McpDialogToken): Unit
    /** Page leave cleanup; ongoing component-owned OAuth and admitted writes are unchanged. */
    public fun hidePage(): Unit
    /** Acknowledge application failure without clearing business text or changing server state. */
    public fun dismissFailure(): Unit
    /** Idempotently release local observations/interactions, never application queue/shared clients. */
    public override fun close(): Unit
}

/** Inject ports and explicit owner scope; no backend/service locator is created by the factory. */
public fun interface McpSettingsViewModelFactory {
    public fun create(
        dependencies: McpSettingsDependencies,
        ownerScope: CoroutineScope,
    ): McpSettingsViewModel
}
