package io.github.stream29.kodex.app.application.contract

import io.github.stream29.kodex.app.agent.contract.AgentSettingsViewModel
import io.github.stream29.kodex.app.pathpicker.contract.DirectoryPickerViewModel
import io.github.stream29.kodex.app.session.contract.SessionViewModel
import io.github.stream29.kodex.app.sessioncatalog.contract.SessionCatalogViewModel
import io.github.stream29.kodex.app.settings.contract.SettingsViewModel
import io.github.stream29.kodex.app.settings.contract.OpenAiLoginViewModel
import kotlinx.coroutines.flow.StateFlow
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

/** Editable state and command boundary for one Rename Session popup. */
public interface RenameSessionPopupViewModel : AutoCloseable {
    public val target: SessionViewModel
    public val draftName: StateFlow<String>

    public fun updateDraftName(name: String): Unit

    /**
     * Trims and applies the latest [draftName] to [target].
     *
     * The frontend submits this command directly from the text input's Enter
     * key and dismisses the exact open handle after it returns.
     */
    public suspend fun rename(): Unit

    override fun close(): Unit
}

/** Captured target and command boundary for one Delete Session popup. */
public interface DeleteSessionPopupViewModel : AutoCloseable {
    public val sessionIndex: Int

    /** Root Session title captured when the popup opened, when one is available. */
    public val threadName: String?

    /**
     * Deletes the captured persisted Session.
     *
     * Returns false when the captured target no longer exists. A successful
     * deletion does not dismiss the parent popup.
     */
    public suspend fun delete(): Boolean

    override fun close(): Unit
}
