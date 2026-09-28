package io.github.stream29.kodex.app.pathpicker.contract

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.files.Path

/** UI-framework-free state machine for one short-lived directory picker. */
public interface DirectoryPickerViewModel : AutoCloseable {
    public val state: StateFlow<DirectoryPickerState>
    public val effects: Flow<DirectoryPickerEffect>

    public fun navigateTo(directory: Path): Unit

    public fun navigateUp(): Unit

    public fun updateFilter(query: String): Unit

    public fun clearFilter(): Unit

    public fun retry(): Unit

    /** Validates the current request and emits its resolved directory without waiting for children. */
    public fun confirm(): Unit

    override fun close(): Unit
}
