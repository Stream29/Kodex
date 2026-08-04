/*
 * Derived from Ktor's Curl client engine.
 * Copyright 2014-2026 JetBrains s.r.o and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

@file:OptIn(ExperimentalForeignApi::class)

package io.github.stream29.kodex.utils.ktorclientext.kodexcurl

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.curl.Curl
import io.ktor.client.engine.engines
import io.ktor.utils.io.InternalAPI
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import libcurl.CURL_GLOBAL_ALL
import libcurl.curl_global_init

// curl_global_init must run once, before this engine creates a transfer thread.
@Suppress("DEPRECATION")
@OptIn(ExperimentalStdlibApi::class)
@EagerInitialization
private val curlGlobalInitReturnCode: Int = curl_global_init(CURL_GLOBAL_ALL.convert()).convert()

@Suppress("unused", "DEPRECATION")
@OptIn(ExperimentalStdlibApi::class, InternalAPI::class)
@EagerInitialization
private val initializeKodexCurl: Unit = run {
    Curl.let {
        engines.append(KodexCurl)
    }
}

@OptIn(InternalAPI::class)
internal data object KodexCurl : HttpClientEngineFactory<KodexCurlEngineConfig> {
    override fun create(block: KodexCurlEngineConfig.() -> Unit): HttpClientEngine {
        check(curlGlobalInitReturnCode == 0) {
            "curl_global_init() returned non-zero: $curlGlobalInitReturnCode"
        }
        return KodexCurlClientEngine(KodexCurlEngineConfig().apply(block))
    }
}
