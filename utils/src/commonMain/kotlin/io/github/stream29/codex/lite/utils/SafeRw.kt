package io.github.stream29.codex.lite.utils

import kotlinx.coroutines.sync.withLock
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

public class SafeRw<out ImmutableType, out MutableType : ImmutableType>(
    @PublishedApi
    internal val value: MutableType
) {
    @PublishedApi
    internal val rwMutex: ReadWriteMutex = ReadWriteMutex()

    @OptIn(ExperimentalContracts::class)
    public suspend inline fun <T> readSession(block: (ImmutableType) -> T): T {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }
        return rwMutex.reader.withLock {
            block(value)
        }
    }

    @OptIn(ExperimentalContracts::class)
    public suspend inline fun <T> writeSession(block: (MutableType) -> T): T {
        contract {
            callsInPlace(block, InvocationKind.EXACTLY_ONCE)
        }
        return rwMutex.writer.withLock {
            block(value)
        }
    }
}
