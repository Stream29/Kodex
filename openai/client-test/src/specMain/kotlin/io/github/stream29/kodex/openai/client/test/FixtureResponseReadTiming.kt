package io.github.stream29.kodex.openai.client.test

import io.github.stream29.kodex.openai.ResponsesStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/** Adds an explicit fake elapsed time for a matching created/completed pair in a synthetic response stream. */
public expect fun Flow<ResponsesStreamEvent>.withFixtureResponseReadTiming(elapsed: Duration): Flow<ResponsesStreamEvent>
