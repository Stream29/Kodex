package io.github.stream29.kodex.tool.applypatch

/** Model-facing description of the freeform patch tool. */
public const val ApplyPatchDescription: String =
    "Use the `apply_patch` tool to edit files. This is a FREEFORM tool, so do not wrap the patch in JSON."

/** Grammar advertised to the model for the freeform patch payload. */
public val ApplyPatchGrammar: String =
    """
    start: begin_patch environment_id? hunk+ end_patch
    begin_patch: "*** Begin Patch" LF
    environment_id: "*** Environment ID: " filename LF
    end_patch: "*** End Patch" LF?

    hunk: add_hunk | delete_hunk | update_hunk
    add_hunk: "*** Add File: " filename LF add_line+
    delete_hunk: "*** Delete File: " filename LF
    update_hunk: "*** Update File: " filename LF change_move? change?
    filename: /(.+)/
    add_line: "+" /(.*)/ LF -> line

    change_move: "*** Move to: " filename LF
    change: (change_context | change_line)+ eof_line?
    change_context: ("@@" | "@@ " /(.+)/) LF
    change_line: ("+" | "-" | " ") /(.*)/ LF
    eof_line: "*** End of File" LF

    %import common.LF
    """.trimIndent()
