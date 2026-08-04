/*
 * Derived from Ktor's Curl client engine.
 * Copyright 2014-2026 JetBrains s.r.o and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package io.github.stream29.kodex.utils.ktorclientext.kodexcurl

import io.ktor.client.engine.HttpClientEngineConfig

internal class KodexCurlEngineConfig : HttpClientEngineConfig() {
    internal var forceProxyTunneling: Boolean = false
    internal var caInfo: String? = null
    internal var caPath: String? = null
    internal var sslVerify: Boolean = true
}
