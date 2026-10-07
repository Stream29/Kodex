package io.github.stream29.kodex.cli.rpc

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow

/**
 * A borrowed field port whose value always reads the current source authority.
 * Collection conflates the selected field without an asynchronously scheduled mirror or new store.
 */
@OptIn(InternalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)
internal fun <T, R> StateFlow<T>.projectState(select: (T) -> R): StateFlow<R> {
    val source = this
    return object : StateFlow<R> {
        override val value: R get() = select(source.value)
        override val replayCache: List<R> get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<R>): Nothing {
            var initialized = false
            var previous: R? = null
            return source.collect(object : FlowCollector<T> {
                override suspend fun emit(value: T) {
                    val next = select(value)
                    if (!initialized || next != previous) {
                        initialized = true
                        previous = next
                        collector.emit(next)
                    }
                }
            })
        }
    }
}
