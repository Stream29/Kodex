package io.github.stream29.kodex.app.sessionrename

import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameDependencies
import io.github.stream29.kodex.app.sessionrename.contract.SessionRenameViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

public fun createSessionRenameViewModel(
    initialName: String,
    dependencies: SessionRenameDependencies,
): SessionRenameViewModel = DefaultSessionRenameViewModel(initialName, dependencies)

private class DefaultSessionRenameViewModel(
    initialName: String,
    private val dependencies: SessionRenameDependencies,
) : SessionRenameViewModel {
    private val mutableDraftName = MutableStateFlow(initialName)
    override val draftName: StateFlow<String> = mutableDraftName.asStateFlow()
    override var isActive: Boolean = true
        private set

    override fun updateDraftName(name: String) {
        if (isActive) mutableDraftName.value = name
    }

    override suspend fun rename() {
        check(isActive) { "Rename Session popup is closed." }
        dependencies.rename(mutableDraftName.value.trim())
    }

    override fun close() {
        isActive = false
    }
}
