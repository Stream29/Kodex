@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.cli.history

/**
 * Renderer-local History position and input source, independent of business history ownership.
 *
 * A host may retain this exact instance by exact History ViewModel identity across tab unmounts.
 * It must discard it on binding replacement/close, and must not mount two lists using the same
 * instance simultaneously.
 */
public expect class AgentHistoryViewState()
