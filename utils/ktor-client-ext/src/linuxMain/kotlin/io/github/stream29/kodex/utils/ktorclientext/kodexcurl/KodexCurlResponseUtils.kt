/*
 * Derived from Ktor's Curl client engine.
 * Copyright 2014-2026 JetBrains s.r.o and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package io.github.stream29.kodex.utils.ktorclientext.kodexcurl

import io.ktor.http.HeadersBuilder
import io.ktor.http.cio.HttpHeadersMap

internal fun HttpHeadersMap.toBuilder(): HeadersBuilder = HeadersBuilder().also { builder ->
    for (offset in offsets()) {
        builder.append(nameAtOffset(offset).toString(), valueAtOffset(offset).toString())
    }
}
