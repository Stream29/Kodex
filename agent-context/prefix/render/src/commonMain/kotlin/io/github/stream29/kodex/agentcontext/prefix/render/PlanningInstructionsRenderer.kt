package io.github.stream29.kodex.agentcontext.prefix.render

/** Renders the fixed guidance for the always-available `update_plan` tool. */
public fun renderPlanningInstructions(): String = PlanningInstructions

// Kept verbatim with codex-rs/protocol/src/prompts/base_instructions/default.md.
private val PlanningInstructions: String =
    """
    ## Planning

    You have access to an `update_plan` tool which tracks steps and progress and renders them to the user. Using the tool helps demonstrate that you've understood the task and convey how you're approaching it. Plans can help to make complex, ambiguous, or multi-phase work clearer and more collaborative for the user. A good plan should break the task into meaningful, logically ordered steps that are easy to verify as you go.

    Note that plans are not for padding out simple work with filler steps or stating the obvious. The content of your plan should not involve doing anything that you aren't capable of doing (i.e. don't try to test things that you can't test). Do not use plans for simple or single-step queries that you can just do or answer immediately.

    Do not repeat the full contents of the plan after an `update_plan` call — the harness already displays it. Instead, summarize the change made and highlight any important context or next step.

    Before running a command, consider whether or not you have completed the previous step, and make sure to mark it as completed before moving on to the next step. It may be the case that you complete all steps in your plan after a single pass of implementation. If this is the case, you can simply mark all the planned steps as completed. Sometimes, you may need to change plans in the middle of a task: call `update_plan` with the updated plan and make sure to provide an `explanation` of the rationale when doing so.

    Use a plan when:

    - The task is non-trivial and will require multiple actions over a long time horizon.
    - There are logical phases or dependencies where sequencing matters.
    - The work has ambiguity that benefits from outlining high-level goals.
    - You want intermediate checkpoints for feedback and validation.
    - When the user asked you to do more than one thing in a single prompt
    - The user has asked you to use the plan tool (aka "TODOs")
    - You generate additional steps while working, and plan to do them before yielding to the user

    ## `update_plan`

    A tool named `update_plan` is available to you. You can use it to keep an up‑to‑date, step‑by‑step plan for the task.

    To create a new plan, call `update_plan` with a short list of 1‑sentence steps (no more than 5-7 words each) with a `status` for each step (`pending`, `in_progress`, or `completed`).

    When steps have been completed, use `update_plan` to mark each finished step as `completed` and the next step you are working on as `in_progress`. There should always be exactly one `in_progress` step until everything is done. You can mark multiple items as complete in a single `update_plan` call.

    If all steps are complete, ensure you call `update_plan` to mark all steps as `completed`.
    """.trimIndent() + "\n"
