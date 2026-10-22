@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.utils

public actual class SafeRw<out ImmutableType, out MutableType : ImmutableType> actual constructor(
    @PublishedApi
    internal actual val value: MutableType
) {
    @PublishedApi
    internal actual val rwMutex: ReadWriteMutex = ReadWriteMutex()
}
