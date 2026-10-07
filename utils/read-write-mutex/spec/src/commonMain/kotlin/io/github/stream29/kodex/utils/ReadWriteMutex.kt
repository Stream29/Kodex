package io.github.stream29.kodex.utils

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * Read/write synchronization boundary.
 *
 * [reader] permits concurrent read sessions and excludes [writer]. [writer]
 * excludes both readers and other writers. [stateFlow] reports the observable
 * ownership state and is updated together with successful lock transitions.
 */
public interface ReadWriteMutex {
    public sealed interface State {
        public sealed interface Readable : State
        public data object Free : Readable
        public data class Read(public val count: Int) : Readable
        public data object Write : State
    }

    public val stateFlow: StateFlow<State>

    /**
     * A read lock proxy. Multiple readers may hold it concurrently.
     *
     * The returned [Mutex] is a session boundary: callers must unlock it from
     * the same logical read session that acquired it.
     */
    public val reader: Mutex

    /**
     * A write lock proxy that excludes all readers and other writers.
     *
     * A non-null owner follows [Mutex] identity checks. Rejected unlock by a
     * different owner throws [IllegalStateException] without changing either
     * the held lock or [stateFlow]. The actual owner can still release it.
     */
    public val writer: Mutex
}
