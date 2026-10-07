package io.github.stream29.kodex.app.hooksettings

import io.github.stream29.kodex.rpc.models.NotificationHook
import io.github.stream29.kodex.rpc.models.NotificationHookType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Result of synchronous admission to the existing application write queue, NOT persistence.
 * [Accepted] means the adapter copied the exact captured baseline and update into that queue.
 * The queue retains those values after editor release, page hiding, component close and
 * component-owner cancellation. This is not a persistence receipt: stale baselines and same-name
 * Add conflicts leave settings unchanged under the existing adapter rules, without a conflict
 * failure flag. Unexpected queue/write failures use the application failure flag.
 * [Rejected] means nothing was admitted; retain the draft and show [Rejected.message].
 */
public sealed interface HookWriteAdmission {
    public data object Accepted : HookWriteAdmission
    /** Local, credential-free explanation; never a remote exception message. */
    public data class Rejected(public val message: String) : HookWriteAdmission
}

/**
 * Bound capability captured at editor opening. [original] is null for Add, otherwise the EXACT
 * original Hook, not a later same-name value. save preserves replacement position; Add appends.
 * A stale original or same-name Add leaves settings unchanged, never silently rebases. Accepted
 * admission does not guarantee that the queued update changes the stored value.
 * release drops unaccepted resources only and is idempotent/nonthrowing.
 */
public interface HookEditHandle {
    public val original: NotificationHook?
    /** @throws Exception Unexpected admission failure, reported by the component exactly once. */
    public fun save(updated: NotificationHook): HookWriteAdmission
    public fun release(): Unit
}

/**
 * Typed frontend ports; no full settings, store, executor or private application queue escapes.
 * hooks preserves persisted order. operationFailure is application-owned and survives reopen.
 * All methods run on the owner/UI dispatcher. No method executes the configured shell command.
 */
public interface HookSettingsDependencies {
    public val hooks: StateFlow<List<NotificationHook>>
    public val operationFailure: StateFlow<Boolean>
    /**
     * Null name captures an Add capability; a missing exact named target returns null.
     * @throws Exception Unexpected capture failure; do not expose its text to the renderer.
     */
    public fun captureEditor(name: String?): HookEditHandle?
    /**
     * Delete only if the current entry equals [original]; stale targets follow the existing
     * application queue/store rules (unchanged settings, not a conflict failure). Admission copies
     * the original and survives component close.
     * @throws Exception Unexpected admission failure.
     */
    public fun delete(original: NotificationHook): HookWriteAdmission
    /** Nonthrowing application-local reporting; never log raw settings. Cancellation is excluded. */
    public fun reportFailure(failure: Throwable): Unit
    /** Nonthrowing acknowledgement of the application-owned failure flag. */
    public fun dismissFailure(): Unit
}

/** Identity token for one open interaction. Forged/late tokens are ignored, never retargeted. */
public class HookDialogToken

/** Business editor text is allowed to be invalid until Save; renderer buffers are not authority. */
public data class HookEditorDraft(
    public val name: String = "",
    public val command: String = "",
    public val types: Set<NotificationHookType> = NotificationHookType.entries.toSet(),
)

/**
 * Renderer branches: Hidden draws no dialog; Details displays the latest named value without
 * executing/revealing its command; Editing draws Name, four type toggles, Command and [Editing.error];
 * Deleting draws a cancel-first confirmation of its captured original. Every callback carries
 * the token. There is exactly one active dialog, independent of the application's global popup.
 */
public sealed interface HookSettingsDialog {
    public data object Hidden : HookSettingsDialog
    public data class Details(
        public val token: HookDialogToken,
        public val hook: NotificationHook,
    ) : HookSettingsDialog
    public data class Editing(
        public val token: HookDialogToken,
        public val originalName: String?,
        public val draft: HookEditorDraft,
        public val error: String? = null,
    ) : HookSettingsDialog
    public data class Deleting(
        public val token: HookDialogToken,
        public val original: NotificationHook,
        public val error: String? = null,
    ) : HookSettingsDialog
}

/**
 * Render ordered name/type rows and Add; empty hooks renders "None configured". Failure renders
 * a generic acknowledgement, not a persisted-success or command-execution indicator. Closed
 * freezes the final list, hides the dialog, disables actions; hosts stop rendering it.
 */
public data class HookSettingsState(
    public val hooks: List<NotificationHook>,
    public val dialog: HookSettingsDialog = HookSettingsDialog.Hidden,
    public val operationFailure: Boolean = false,
    public val closed: Boolean = false,
)

/**
 * Complete Hook list/detail/editor/delete owner, dispatcher-confined to the factory owner scope.
 * Methods are synchronous, nonthrowing except CancellationException from dependency capture/admission,
 * which propagates unchanged (never reported as business failure). Unexpected port failures are
 * reported exactly once and shown generically. Close is idempotent, stops only child observations
 * and rejects all future commands; it never closes application stores or cancels admitted writes.
 * Owner termination performs the same cleanup. Page hiding discards only unaccepted interaction.
 */
public interface HookSettingsViewModel : AutoCloseable {
    public val state: StateFlow<HookSettingsState>
    /** Capture Add immediately; replaced unaccepted handles are released.
     * @throws kotlinx.coroutines.CancellationException Dependency capture was cancelled.
     */
    public fun add(): Unit
    /** Show latest exact-name detail; missing name is a no-op. */
    public fun details(name: String): Unit
    /** From exact Details token, capture editor baseline before accepting any input.
     * @throws kotlinx.coroutines.CancellationException Dependency capture was cancelled.
     */
    public fun edit(token: HookDialogToken): Unit
    /** From exact Details token, capture original for delete confirmation. */
    public fun requestDelete(token: HookDialogToken): Unit
    /**
     * Applies [update] synchronously to this token's latest VM-owned draft and clears validation.
     * A late/noneditor token is ignored without invoking [update]. The pure, non-reentrant
     * update is invoked once; it must not capture a prior whole draft, perform I/O or call commands.
     * Consequently multiple field callbacks before recomposition preserve each other's edits.
     *
     * @throws Exception when [update] fails; the draft and validation remain unchanged.
     */
    public fun updateDraft(token: HookDialogToken, update: (HookEditorDraft) -> HookEditorDraft): Unit
    /**
     * Trim name only (baseline renderer behavior); preserve command. Validate nonblank name,
     * command and nonempty four-type selection. Invalid/rejected admission retains the draft.
     * Accepted admission hides once, notifies no persistence success, and cannot be repeated.
     * @throws kotlinx.coroutines.CancellationException Dependency admission was cancelled.
     */
    public fun save(token: HookDialogToken): Unit
    /** Admit the captured original once, never a newer same-name entry.
     * @throws kotlinx.coroutines.CancellationException Dependency admission was cancelled.
     */
    public fun confirmDelete(token: HookDialogToken): Unit
    /** Dismiss only the matching active token, discarding unaccepted drafts. */
    public fun dismiss(token: HookDialogToken): Unit
    /** Settings page switch: discard dialog/handle, retain application observation/failure. */
    public fun hidePage(): Unit
    public fun dismissFailure(): Unit
    public override fun close(): Unit
}

/** Factory never creates a store/queue; owner scope owns observations, not admitted writes. */
public fun interface HookSettingsViewModelFactory {
    public fun create(
        dependencies: HookSettingsDependencies,
        ownerScope: CoroutineScope,
    ): HookSettingsViewModel
}
