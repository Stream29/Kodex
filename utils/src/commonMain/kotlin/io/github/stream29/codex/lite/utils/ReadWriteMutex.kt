package io.github.stream29.codex.lite.utils

import io.github.stream29.codex.lite.utils.ReadWriteMutex.State
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.selects.SelectClause2
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.ExperimentalAtomicApi

public interface ReadWriteMutex {
    public sealed interface State {
        public data object Free: State
        public data class Read(public val count: Int) : State
        public data object Write : State
    }
    public val stateFlow: StateFlow<State>

    /**
     * This [Mutex] proxy instance is not a usual [Mutex].
     * Multiple readers can hold this [reader] at the same time.
     */
    public val reader: Mutex
    public val writer: Mutex
}

public fun ReadWriteMutex(): ReadWriteMutex = ReadWriteMutexImpl()

@OptIn(ExperimentalAtomicApi::class)
private class ReadWriteMutexImpl : ReadWriteMutex {
    override val stateFlow: StateFlow<State>
        field = MutableStateFlow<State>(State.Free)
    private val internalWriter = Mutex()
    private val pendingReader = Mutex()
    override val writer: Mutex = object : Mutex {
        override val isLocked: Boolean by internalWriter::isLocked
        @Deprecated(
            "Mutex.onLock deprecated without replacement. For additional details please refer to #2794",
            level = DeprecationLevel.WARNING
        )
        override val onLock: SelectClause2<Any?, Mutex> by internalWriter::onLock
        override fun tryLock(owner: Any?): Boolean {
            val lockSuccess = internalWriter.tryLock(owner)
            if (lockSuccess) {
                val stateCorrect = stateFlow.compareAndSet(State.Free, State.Write)
                if (!stateCorrect) {
                    error("State is broken")
                }
            }
            return lockSuccess
        }
        override suspend fun lock(owner: Any?) {
            return internalWriter.lock(owner).also {
                val stateCorrect = stateFlow.compareAndSet(State.Free, State.Write)
                if (!stateCorrect) {
                    error("State is broken")
                }
            }
        }
        override fun holdsLock(owner: Any): Boolean = internalWriter.holdsLock(owner)
        override fun unlock(owner: Any?) {
            val stateCorrect = stateFlow.compareAndSet(State.Write, State.Free)
            if (!stateCorrect) {
                error("State is broken")
            }
            internalWriter.unlock(owner)
        }
    }

    override val reader: Mutex = object : Mutex {
        override val isLocked: Boolean
            get() = stateFlow.value is State.Write
        @Deprecated(
            "Mutex.onLock deprecated without replacement. For additional details please refer to #2794",
            level = DeprecationLevel.WARNING
        )
        override val onLock: SelectClause2<Any?, Mutex>
            get() = TODO("Not yet implemented")

        override fun tryLock(owner: Any?): Boolean {
            val newState = stateFlow.getAndUpdate { state ->
                when (state) {
                    is State.Read -> State.Read(state.count + 1)
                    is State.Write -> state
                    is State.Free -> State.Free
                }
            }
            when (newState) {
                is State.Write -> return false
                is State.Read -> return true
                is State.Free -> {
                    val getLock = internalWriter.tryLock(owner)
                    if (getLock) {
                        val stateCorrect = stateFlow.compareAndSet(State.Free, State.Read(1))
                        if (!stateCorrect) {
                            error("State is broken")
                        }
                        return true
                    } else {
                        return tryLock(owner)
                    }
                }
            }
        }

        override suspend fun lock(owner: Any?) {
            val newState = stateFlow.getAndUpdate { state ->
                when (state) {
                    is State.Read -> State.Read(state.count + 1)
                    is State.Write -> State.Write
                    is State.Free -> State.Free
                }
            }
            when (newState) {
                is State.Read -> return
                is State.Free -> {
                    val getLock = internalWriter.tryLock(null)
                    if (getLock) {
                        val stateCorrect = stateFlow.compareAndSet(State.Free, State.Read(1))
                        if (!stateCorrect) {
                            error("State is broken")
                        }
                        return
                    } else {
                        lock(owner)
                    }
                }
                is State.Write -> {
                    pendingReader.withLock {
                        if (stateFlow.value !is State.Write) {
                            lock(owner)
                            return
                        }
                        internalWriter.lock(null)
                        val stateCorrect = stateFlow.compareAndSet(State.Free, State.Read(1))
                        if (!stateCorrect) {
                            error("State is broken")
                        }
                        return
                    }
                }
            }
        }

        override fun holdsLock(owner: Any): Boolean = false

        override fun unlock(owner: Any?) {
            val newState = stateFlow.getAndUpdate { state ->
                when (state) {
                    is State.Read -> State.Read(state.count - 1)
                    is State.Write -> error("State is broken")
                    is State.Free -> error("State is broken")
                }
            } as State.Read
            if (newState.count > 0) {
                return
            } else {
                val stateCorrect = stateFlow.compareAndSet(newState, State.Free)
                if (!stateCorrect) {
                    error("State is broken")
                }
                internalWriter.unlock(null)
            }
        }
    }
}
