@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.server

import io.github.stream29.kodex.rpc.models.Notification
import kotlinx.coroutines.flow.Flow

/** Transient observations only; publication cannot backpressure an Agent. */
public expect class BackendNotifications() {
    public val flow: Flow<Notification>
}
