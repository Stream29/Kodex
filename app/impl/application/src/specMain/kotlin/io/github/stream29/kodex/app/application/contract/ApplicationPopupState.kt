package io.github.stream29.kodex.app.application.contract

import io.github.stream29.kodex.app.agent.contract.AgentSettingsViewModel
import io.github.stream29.kodex.app.workingdirectory.contract.WorkingDirectoryViewModel
import io.github.stream29.kodex.app.session.contract.SessionViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModel
import io.github.stream29.kodex.app.settings.contract.SettingsViewModel
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginViewModel
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameViewModel
import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteViewModel

/**
 * The one application-level popup surface.
 *
 * Each open variant is a distinct popup instance with reference identity and a
 * directly renderable child ViewModel. A new instance must be created for every
 * opening, including reopening the same kind of popup. Exact-handle checks use
 * referential identity rather than structural equality.
 *
 * Render only the current variant and consume its child state directly. Keep
 * anchor, geometry and focus in the renderer, and pass this exact open handle
 * to dismiss. Unmounting a renderer does not independently close a borrowed
 * popup child; replacement, dismissal, target closure and Application shutdown
 * release it. Login retains its Settings return handle while hiding that page.
 */
public sealed interface ApplicationPopupState {
    /** No application overlay; render the selected Session surface normally. */
    public data object Closed : ApplicationPopupState

    /** Exact open handle accepted by [ApplicationViewModel.dismissPopup]. */
    public sealed interface Open : ApplicationPopupState

    /** Render the catalog child; its initial refresh is requested by the caller. */
    public class SessionCatalog(
        public val viewModel: SessionCatalogViewModel,
    ) : Open

    /** Render Settings for this captured tab, not the latest selected tab. */
    public class Settings(
        public val target: SessionViewModel,
        public val viewModel: SettingsViewModel,
    ) : Open

    /** Render the Rename child's draft and confirmation for its captured target. */
    public class RenameSession(
        public val viewModel: RenameSessionPopupViewModel,
    ) : Open

    /** Render the Delete child for its captured persisted index. */
    public class DeleteSession(
        public val viewModel: SessionDeleteViewModel,
    ) : Open

    /** Render Login instead of Settings; dismissal restores the same Settings child. */
    public class Login(
        public val viewModel: OpenAiLoginViewModel,
        public val returnTo: Settings,
    ) : Open

    /** Render the directory picker child bound to the captured owner. */
    public class WorkingDirectory(
        public val viewModel: WorkingDirectoryPopupViewModel,
    ) : Open
}

/** Application ownership adapter; behavior is defined by [WorkingDirectoryViewModel]. */
public interface WorkingDirectoryPopupViewModel : WorkingDirectoryViewModel {
    /** Captured owner used to dispose this exact popup when its Session/Agent closes. */
    public val target: AgentSettingsViewModel
}

/** Application ownership adapter; interaction behavior is defined by [SessionRenameViewModel]. */
public interface RenameSessionPopupViewModel : SessionRenameViewModel {
    /** Exact captured Session, used by the parent to validate child ownership. */
    public val target: SessionViewModel
}
