package io.github.stream29.kodex.app.application.contract

import io.github.stream29.kodex.app.agent.contract.AgentSettingsViewModel
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import io.github.stream29.kodex.app.session.contract.SessionViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModel
import io.github.stream29.kodex.app.settings.contract.SettingsViewModel
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginViewModel
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameViewModel
import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteViewModel
import kotlinx.io.files.Path

/**
 * The one application-level popup surface.
 *
 * Each open variant is a distinct popup instance with reference identity and a
 * directly renderable child ViewModel. A new instance must be created for every
 * opening, including reopening the same kind of popup. Exact-handle checks use
 * referential identity rather than structural equality.
 */
public sealed interface ApplicationPopupState {
    public data object Closed : ApplicationPopupState

    /** Exact open handle accepted by [ApplicationViewModel.dismissPopup]. */
    public sealed interface Open : ApplicationPopupState

    public class SessionCatalog(
        public val viewModel: SessionCatalogViewModel,
    ) : Open

    public class Settings(
        public val target: SessionViewModel,
        public val viewModel: SettingsViewModel,
    ) : Open

    public class RenameSession(
        public val viewModel: RenameSessionPopupViewModel,
    ) : Open

    public class DeleteSession(
        public val viewModel: DeleteSessionPopupViewModel,
    ) : Open

    public class Login(
        public val viewModel: OpenAiLoginViewModel,
        public val returnTo: Settings,
    ) : Open

    public class WorkingDirectory(
        public val viewModel: WorkingDirectoryPopupViewModel,
    ) : Open
}

/** Captured settings target and directory-picker child for one cwd popup. */
public interface WorkingDirectoryPopupViewModel : AutoCloseable {
    public val target: AgentSettingsViewModel
    public val picker: DirectoryPickerViewModel

    /** Applies [directory] to the captured target, then closes this child. */
    public suspend fun select(directory: Path): Unit

    override fun close(): Unit
}

/** Application ownership adapter; interaction behavior is defined by [SessionRenameViewModel]. */
public interface RenameSessionPopupViewModel : SessionRenameViewModel {
    /** Exact captured Session, used by the parent to validate child ownership. */
    public val target: SessionViewModel
}

/** Compatibility name for the independent deletion component. */
public typealias DeleteSessionPopupViewModel = SessionDeleteViewModel
