package io.github.stream29.kodex.hook.contract.error

import kotlinx.io.files.Path

/** Invocation boundary for unhandled application errors. */
public interface ErrorHooks {
    public suspend fun onUnhandledError(message: String?, cwd: Path)
}

/** Error hook boundary that performs no operations. */
public data object NoOpErrorHooks : ErrorHooks {
    override suspend fun onUnhandledError(message: String?, cwd: Path) {}
}
