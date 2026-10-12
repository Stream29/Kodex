package io.github.stream29.kodex.app.settings.contract

/** Built-in context roots that can be disabled but not edited or removed. */
public enum class BuiltInContextSource {
    /** Render "Agents home" with "~/.agents/"; only its enabled flag is editable. */
    AgentsHome,
    /** Render "Kodex home" with "~/.kodex/"; this is not a custom-source row. */
    KodexHome,
    /** Render "Codex home" with "~/.codex/"; disabling does not delete that directory. */
    CodexHome,
    /** Render "Git root" with "<git-root>/"; discovery stays in the backend context owner. */
    GitRoot,
    /** Render "Working directory" with "<cwd>/"; toggling does not change Session cwd. */
    WorkingDirectory,
}
