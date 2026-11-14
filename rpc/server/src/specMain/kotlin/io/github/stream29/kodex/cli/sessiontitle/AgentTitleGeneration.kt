package io.github.stream29.kodex.cli.sessiontitle

import io.github.stream29.kodex.agentstate.contract.KodexAgentState
import io.github.stream29.kodex.cli.settings.SessionTitleSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Dedicated automatic session-title capability attached when a new Session is created.
 *
 * Runs as an auxiliary child [Job] in [scope] (typically the live Session/runtime scope) and
 * observes [agentState]'s committed history and `cacheNonce`:
 * - Image-only and blank user messages leave first-text eligibility open.
 * - The first committed nonblank user text consumes the one-shot gate: if [settings] is enabled
 *   and the current `threadName` still matches the default `Session <n>` pattern, calls [generator]
 *   and writes the generated title via settings CAS (abandoning if a manual rename changed
 *   `threadName` or if history replacement changed `cacheNonce`).
 * - If destructive history replacement (`revert`) removes every committed nonblank user text,
 *   cancels any in-flight title request and reopens first-text eligibility.
 */
public expect fun attachSessionTitleGeneration(
    scope: CoroutineScope,
    agentState: KodexAgentState,
    settings: () -> SessionTitleSettings,
    generator: SessionTitleGenerator,
): Job
