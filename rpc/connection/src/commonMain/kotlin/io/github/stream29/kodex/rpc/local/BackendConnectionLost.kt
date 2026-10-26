@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.local

public actual class BackendConnectionLost actual constructor(message: String, cause: Throwable?) :
    IllegalStateException(message, cause)
