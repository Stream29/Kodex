package io.github.stream29.codex.lite.utils.shellclient

/** Minimal settings view for components that observe the active shell. */
public interface ShellSettings {
    /** Shell captured when a new shell operation starts. */
    public val shell: Shell
}
