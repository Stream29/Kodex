/*
 * Derived from Ktor's Curl client engine.
 * Copyright 2014-2026 JetBrains s.r.o and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

@file:OptIn(ExperimentalForeignApi::class)

package io.github.stream29.kodex.utils.ktorclientext.kodexcurl

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.cinterop.*
import libcurl.*

internal typealias EasyHandle = COpaquePointer
internal typealias MultiHandle = COpaquePointer

internal val DISALLOWED_WEBSOCKET_HEADERS = setOf(
    HttpHeaders.Upgrade,
    HttpHeaders.Connection,
    HttpHeaders.SecWebSocketVersion,
    HttpHeaders.SecWebSocketKey,
)

internal fun CURLMcode.verify() {
    check(this == CURLM_OK) {
        "Unexpected Curl multi error: ${curl_multi_strerror(this)?.toKString()} ($this)"
    }
}

internal fun CURLcode.verify() {
    check(this == CURLE_OK) {
        "Unexpected Curl error: $errorMessage"
    }
}

internal val CURLcode.errorMessage: String
    get() = "${curl_easy_strerror(this)?.toKString()} ($this)"

internal fun EasyHandle.option(option: CURLoption, optionValue: Int) {
    curl_easy_setopt(this, option, optionValue).verify()
}

internal fun EasyHandle.option(option: CURLoption, optionValue: Long) {
    curl_easy_setopt(this, option, optionValue).verify()
}

internal fun EasyHandle.option(option: CURLoption, optionValue: CPointer<*>) {
    curl_easy_setopt(this, option, optionValue).verify()
}

internal fun EasyHandle.option(option: CURLoption, optionValue: CValuesRef<*>) {
    curl_easy_setopt(this, option, optionValue).verify()
}

internal fun EasyHandle.option(option: CURLoption, optionValue: String) {
    curl_easy_setopt(this, option, optionValue).verify()
}

internal fun EasyHandle.getInfo(info: CURLINFO, optionValue: CPointer<*>) {
    curl_easy_getinfo(this, info, optionValue).verify()
}

@OptIn(InternalAPI::class, ExperimentalForeignApi::class)
internal fun HttpRequestData.headersToKodexCurl(): CPointer<curl_slist> {
    var result: CPointer<curl_slist>? = null
    fun append(header: String) {
        val appended = curl_slist_append(result, header)
        if (appended == null) {
            curl_slist_free_all(result)
            error("Could not append Curl request header")
        }
        result = appended
    }

    val isUpgradeRequest = isUpgradeRequest()
    forEachHeader { key, value ->
        if (isUpgradeRequest && key in DISALLOWED_WEBSOCKET_HEADERS) return@forEachHeader
        append("$key: $value")
    }
    append("Expect:")
    return checkNotNull(result)
}

internal fun Long.fromKodexCurl(): HttpProtocolVersion = when (this) {
    CURL_HTTP_VERSION_1_0 -> HttpProtocolVersion.HTTP_1_0
    CURL_HTTP_VERSION_1_1 -> HttpProtocolVersion.HTTP_1_1
    CURL_HTTP_VERSION_2_0 -> HttpProtocolVersion.HTTP_2_0
    else -> HttpProtocolVersion.HTTP_1_1
}
