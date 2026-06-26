package io.github.stream29.codex.lite.utils.hosttest

/**
 * @return Nullable because environment variables may be unset; `null` means the
 * requested variable is absent on the current host.
 */
public expect fun environmentVariable(name: String): String?
