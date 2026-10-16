package io.github.stream29.kodex.app.sessiondelete

import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteDependencies
import io.github.stream29.kodex.app.sessiondelete.contract.SessionDeleteViewModel

public fun createSessionDeleteViewModel(
    sessionIndex: Int,
    threadName: String?,
    dependencies: SessionDeleteDependencies,
): SessionDeleteViewModel = DefaultSessionDeleteViewModel(sessionIndex, threadName, dependencies)

private class DefaultSessionDeleteViewModel(
    override val sessionIndex: Int,
    override val threadName: String?,
    private val dependencies: SessionDeleteDependencies,
) : SessionDeleteViewModel {
    override var isActive: Boolean = true
        private set

    override suspend fun delete(): Boolean {
        check(isActive) { "Delete Session popup is closed." }
        return dependencies.delete(sessionIndex)
    }

    override fun close() {
        isActive = false
    }
}
