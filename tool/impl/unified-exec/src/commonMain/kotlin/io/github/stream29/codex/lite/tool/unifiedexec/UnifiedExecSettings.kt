package io.github.stream29.codex.lite.tool.unifiedexec

import io.github.stream29.codex.lite.utils.shellclient.Shell

/** Narrow global-settings view observed by unified shell execution. */
public interface UnifiedExecSettings {
    /** Default shell captured when a new process starts. */
    public val shell: Shell
}
