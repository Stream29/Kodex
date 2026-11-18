package io.github.stream29.kodex.cli.app

import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.groups.required
import com.github.ajalt.clikt.parameters.groups.single
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.switch

public enum class AgentSqlInput { Stdin, Schema }

/** Queries the chosen backend's public read-only model, not an implicitly selected Agent. */
public class AgentSqlCommand : KodexToolLeafCommand("sql", "Execute bounded read-only SQL or describe the public query model.") {
    public val input: AgentSqlInput by mutuallyExclusiveOptions(
        option(help = "Read one UTF-8 SQL statement from standard input (maximum 64 KiB).")
            .switch("--stdin" to AgentSqlInput.Stdin),
        option(help = "Print public relations, functions, and query examples without running a query.")
            .switch("--schema" to AgentSqlInput.Schema),
    ).single().required()
}
