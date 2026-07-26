package io.github.stream29.codex.lite.agentstate.test

import io.github.stream29.codex.lite.agentcontext.prefix.agentsmd.contract.AgentsMdInstructions
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentContextPrefix
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentContextPrefixProvider
import io.github.stream29.codex.lite.utils.shellclient.Shell
import io.github.stream29.codex.lite.utils.shellclient.ShellType
import kotlinx.io.files.Path

/** Fixed host context for tests that do not exercise context projection. */
public val TestContextPrefixProvider: AgentContextPrefixProvider = { _ ->
    AgentContextPrefix(
        cwd = Path("."),
        shell = Shell(ShellType.Sh, Path("sh")),
        agentMd = AgentsMdInstructions(),
        availableSkills = emptyList(),
    )
}
