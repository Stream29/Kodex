package io.github.stream29.kodex.utils

import io.github.stream29.kodex.utils.ReadWriteMutex.State
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.SelectClause2
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Creates the standard atomic implementation of [ReadWriteMutex]. */
public fun ReadWriteMutex(): ReadWriteMutex = ReadWriteMutexImpl()

@OptIn(ExperimentalAtomicApi::class)
private class ReadWriteMutexImpl : ReadWriteMutex {
    override val stateFlow: StateFlow<State>
        field = MutableStateFlow<State>(State.Free)

    /**
     * Every writer holds this lock while write session.
     */
    private val canWrite = Mutex()

    /**
     * This lock must be acquirable for every read session.
     * But reader sessions don't really hold this lock.
     * A writer must hold this lock to block reader attempts.
     */
    private val canRead = Mutex()
    override val writer: Mutex = object : Mutex {
        override val isLocked: Boolean
            get() = canWrite.isLocked

        /**
         * Nullable select payload type mirrors the deprecated kotlinx.coroutines
         * `Mutex` API; `null` represents an ownerless lock request.
         */
        @Deprecated(
            "Mutex.onLock deprecated without replacement. For additional details please refer to #2794",
            level = DeprecationLevel.WARNING
        )
        override val onLock: SelectClause2<Any?, Mutex>
            get() = throw UnsupportedOperationException("Deprecated")

        /**
         * @param owner Nullable to mirror kotlinx.coroutines `Mutex`; `null`
         * means the write lock is ownerless.
         */
        override fun tryLock(owner: Any?): Boolean {
            if (!canRead.tryLock(owner)) return false
            if (!canWrite.tryLock(owner)) {
                canRead.unlock(owner)
                return false
            }
            stateFlow.acquireWriteOrThrow()
            return true
        }

        /**
         * @param owner Nullable to mirror kotlinx.coroutines `Mutex`; `null`
         * means the write lock is ownerless.
         */
        override suspend fun lock(owner: Any?) {
            canRead.lock(owner)
            try {
                canWrite.lock(owner)
            } catch (e: Throwable) {
                canRead.unlock(owner)
                throw e
            }
            stateFlow.acquireWriteOrThrow()
        }

        override fun holdsLock(owner: Any): Boolean = canWrite.holdsLock(owner)
        /**
         * @param owner Nullable to mirror kotlinx.coroutines `Mutex`; `null`
         * means the write lock is ownerless.
         */
        override fun unlock(owner: Any?) {
            // Validate before publishing Free: an underlying Mutex rejects a
            // mismatched owner without unlocking, so our state must stay Write.
            if (owner != null) {
                check(canWrite.holdsLock(owner)) { "The writer is not locked by this owner." }
            }
            stateFlow.releaseWriteOrThrow()
            canWrite.unlock(owner)
            canRead.unlock(owner)
        }
    }

    override val reader: Mutex = object : Mutex {
        override val isLocked: Boolean
            get() = stateFlow.value is State.Write

        /**
         * Nullable select payload type mirrors the deprecated kotlinx.coroutines
         * `Mutex` API; `null` represents an ownerless lock request.
         */
        @Deprecated(
            "Mutex.onLock deprecated without replacement. For additional details please refer to #2794",
            level = DeprecationLevel.WARNING
        )
        override val onLock: SelectClause2<Any?, Mutex>
            get() = throw UnsupportedOperationException("Deprecated")

        /**
         * @param owner Nullable to mirror kotlinx.coroutines `Mutex`; `null`
         * means the read lock is ownerless.
         */
        override fun tryLock(owner: Any?): Boolean {
            if (!canRead.tryLock(owner)) return false
            try {
                val newState = stateFlow.updateAndGet { state ->
                    when (state) {
                        is State.Free -> {
                            if (canWrite.tryLock(null)) State.Read(1)
                            else State.Free
                        }

                        is State.Read -> State.Read(state.count + 1)
                        is State.Write -> error("State is broken: fail to acquire read with state Write")
                    }
                }
                return newState is State.Read
            } finally {
                canRead.unlock(owner)
            }
        }

        /**
         * @param owner Nullable to mirror kotlinx.coroutines `Mutex`; `null`
         * means the read lock is ownerless.
         */
        override suspend fun lock(owner: Any?) {
            canRead.withLock(owner) {
                while (true) {
                    when (val state = stateFlow.value) {
                        is State.Free -> {
                            canWrite.lock(null)
                            if (stateFlow.compareAndSet(State.Free, State.Read(1))) return
                            canWrite.unlock(null)
                        }

                        is State.Read -> {
                            if (stateFlow.compareAndSet(state, State.Read(state.count + 1))) return
                        }

                        is State.Write -> error("State is broken: fail to acquire read with state Write")
                    }
                }
            }
        }

        override fun holdsLock(owner: Any): Boolean = false

        /**
         * @param owner Nullable to mirror kotlinx.coroutines `Mutex`; `null`
         * means the read lock is ownerless.
         */
        override fun unlock(owner: Any?) {
            val newState = stateFlow.releaseReadOrThrow()
            if (newState is State.Free) {
                canWrite.unlock(null)
            }
        }
    }
}

private fun MutableStateFlow<State>.acquireWriteOrThrow() {
    update { state ->
        when (state) {
            is State.Read -> error("State is broken: fail to acquire write with state Read")
            is State.Write -> error("State is broken: fail to acquire write with state Write")
            is State.Free -> State.Write
        }
    }
}

private fun MutableStateFlow<State>.releaseWriteOrThrow() {
    update { state ->
        when (state) {
            is State.Read -> error("State is broken: fail to release write with state Read")
            is State.Write -> State.Free
            is State.Free -> error("State is broken: fail to release write with state Free")
        }
    }
}

/**
 * Returns the updated state.
 */
private fun MutableStateFlow<State>.releaseReadOrThrow(): State.Readable {
    return updateAndGet { state ->
        when (state) {
            is State.Write -> error("State is broken: fail to release read with state Write")
            is State.Free -> error("State is broken: fail to release read with state Free")
            is State.Read -> when {
                state.count < 1 -> error("State is broken: fail to release read with count < 1")
                state.count == 1 -> State.Free
                else -> State.Read(state.count - 1)
            }
        }
    } as State.Readable
}
